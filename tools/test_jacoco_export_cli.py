#!/usr/bin/env python3
"""Export original JaCoCo XML from retained reports; never recapture coverage."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASE = Path('/private/tmp/kvik-coverage-aggregate-cli/.kvik/tests/coverage-aggregate-cli/case-akvrlrt5')
DEFAULT_SINGLE = Path('/private/var/folders/yh/h124ks9x1vqd0v6sz6slshkc0000gn/T/kvik-coverage-cli-4hyd43oy/first/coverage.json')


def digest(path):
    data = path.read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def snapshot(paths):
    return {str(path): (digest(path), path.stat().st_mtime_ns) for path in sorted(set(paths))}


def originals(report_path, module=None):
    value = json.loads(report_path.read_text())
    contributions = [(value, report_path.parent)] if value['kind'] == 'line-coverage' else [
        (item['report'], report_path.parent/'contributors'/item['digest']['hash']) for item in value['contributors']]
    result = {}
    for report, directory in contributions:
        test_module, target, scope = report['check'].split(':')
        assert target == 'jvm' and scope == 'test'
        for entry in report['originals']['files']:
            parts = entry['path'].split('/')
            if len(parts) != 3 or parts[0] != 'coverage' or parts[2] != 'jacoco.xml':
                continue
            production_module = parts[1]
            if module is not None and module != production_module:
                continue
            name = f'jacoco/{test_module}/{target}/test/{production_module}.xml'
            assert name not in result
            source = directory/entry['path']
            assert digest(source) == entry['digest']
            xml = ET.fromstring(source.read_bytes())
            assert xml.tag == 'report'
            assert {'LINE', 'INSTRUCTION', 'METHOD', 'CLASS'} <= {counter.attrib['type'] for counter in xml.iter('counter')}
            result[name] = source
    assert result
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--case', type=Path, default=DEFAULT_CASE)
    parser.add_argument('--single-report', type=Path, default=DEFAULT_SINGLE)
    parser.add_argument('--schema-root', type=Path)
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/jacoco-export-cli')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    source_case = args.case.resolve(strict=True)
    single = args.single_report.resolve(strict=True)
    reports = {name: source_case/'aggregates'/name/'aggregate.json' for name in ('combined', 'missing', 'unsupported-native')}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    assert not output.is_relative_to(source_case) and not output.is_relative_to(single.parent)
    print('Outputs: '+str(output), flush=True)
    retained = json.loads((source_case/'capture.json').read_text())
    paths = [Path(path) for path in retained['retained']]
    paths += [path for directory in [single.parent, source_case/'project', *(p.parent for p in reports.values())]
              for path in directory.rglob('*') if path.is_file()]
    before = snapshot(paths)
    runs_before = sorted(str(path) for path in source_case.glob('*/runs/*/verification.json'))
    schema = json.loads((args.schema_root/'schemas/v1/contracts.json').read_text()) if args.schema_root else None
    environment = dict(os.environ, PATH='/nonexistent', JAVA_HOME='/nonexistent', KONAN_HOME='/nonexistent')

    def validate(value, name):
        if schema:
            import jsonschema
            jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+name}).validate(value)

    def invoke(label, report=reports['combined'], selection=('--all-modules', '--target', 'jvm'),
               expected=0, xml=None, refusal=False):
        destination = output/label
        command = [str(runtime), 'coverage-export', '--report', str(report), '--format', 'jacoco-xml',
                   *selection, '--output', str(destination)]
        (output/(label+'.command.json')).write_text(json.dumps(command))
        with (output/(label+'.stdout.json')).open('wb') as stdout, (output/(label+'.stderr.log')).open('wb') as stderr:
            process = subprocess.run(command, env=environment, stdout=stdout, stderr=stderr, timeout=120)
        text = (output/(label+'.stdout.json')).read_text()
        assert process.returncode == expected, (label, process.returncode, text, (output/(label+'.stderr.log')).read_text())
        response = json.loads(text)
        if refusal:
            assert not destination.exists()
            diagnostic = response['diagnostic']
            assert diagnostic['code'] == ('KVIK_COVERAGE_EXPORT_ARGUMENTS' if expected == 2 else 'KVIK_COVERAGE_EXPORT_FAILED')
            assert diagnostic['message']
            print('PASS '+label, flush=True)
            return diagnostic
        validate(response, 'coverageConsumerExport')
        metadata = response['result']
        validate(metadata, 'coverageConsumerExportResult')
        assert response['outputDirectory'] == str(destination)
        assert metadata['kind'] == 'coverage-consumer-export' and metadata['format'] == 'jacoco-xml'
        assert metadata['report'] == digest(report) and metadata['conversionLosses'] == []
        assert json.loads((destination/'report/export.json').read_text()) == metadata
        assert metadata['complete'] == (xml is not None)
        expected_files = [] if xml is None else [dict(path=name, executable=False, digest=digest(source)) for name, source in sorted(xml.items())]
        assert metadata['files']['files'] == expected_files
        if xml is not None:
            assert metadata['outcome'] == 'exported'
            for name, source in xml.items():
                assert (destination/'report'/name).read_bytes() == source.read_bytes(), (label, name)
        else:
            assert metadata['outcome'] in ('incomplete', 'unsupported')
        assert {p.relative_to(destination/'report').as_posix() for p in (destination/'report').rglob('*') if p.is_file()} == {
            'export.json', *(xml or {})}
        artifacts = response['artifacts']['files']
        assert len({entry['path'] for entry in artifacts}) == len(artifacts)
        for entry in artifacts:
            assert digest(destination/entry['path']) == entry['digest'] and not entry['executable']
        assert {p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()} == {entry['path'] for entry in artifacts}
        graph = json.loads((destination/'graph.json').read_text())
        validate(graph, 'coverageConsumerExportGraph')
        assert graph['sourceReport'] == digest(report)
        action, = graph['actions']
        result, = graph['results']
        assert action['action']['kind'] == 'generate' and action['action']['cachePolicy'] == 'never'
        assert action['action']['outputs'] == ['report'] and action['dependencies'] == []
        assert result['status'] == 'executed' and action['actionId'] == result['actionId']
        assert {entry['path'] for entry in result['outputs']['files']} == {'report/export.json', *('report/'+name for name in (xml or {}))}
        for entry in result['outputs']['files']:
            assert digest(destination/entry['path']) == entry['digest']
        print('PASS '+label, flush=True)
        return metadata

    all_xml = originals(reports['combined'])
    assert len(all_xml) == 4
    assert {name for name in all_xml if name.endswith('/library.xml')} == {
        'jacoco/consumer/jvm/test/library.xml', 'jacoco/second/jvm/test/library.xml'}
    combined = invoke('combined', xml=all_xml)
    assert combined['sourceStatus'] == combined['status'] == 'passed' and combined['target'] == 'jvm'
    assert combined['modules'] == ['consumer', 'library', 'second']
    library = invoke('library', selection=('--module', 'library', '--target', 'jvm'), xml=originals(reports['combined'], 'library'))
    assert library['modules'] == ['library']
    single_export = invoke('single-default-target', report=single, selection=('--all-modules',), xml=originals(single))
    assert single_export['target'] == 'jvm' and single_export['status'] == 'passed'
    missing = invoke('missing-contributor', report=reports['missing'], expected=3)
    assert missing['sourceStatus'] == missing['status'] == missing['outcome'] == 'incomplete'
    native = invoke('unsupported-native', report=reports['unsupported-native'], selection=('--all-modules', '--target', 'macosArm64'), expected=3)
    assert native['outcome'] == 'unsupported' and native['status'] == 'incomplete'
    selected = invoke('jvm-with-missing-native', report=reports['unsupported-native'], expected=3, xml=all_xml)
    assert selected['sourceStatus'] == selected['status'] == 'incomplete' and selected['complete']

    # Copied metadata variants exercise export semantics; no additional test execution is claimed.
    single_value = json.loads(single.read_text())
    for label in ('unknown-normalized-source', 'failed-source'):
        copied = output/('copied-'+label)
        shutil.copytree(single.parent, copied)
        value = copy.deepcopy(single_value)
        if label == 'unknown-normalized-source':
            value['complete'] = False
            value['status'] = 'incomplete'
            value['sources'][0]['complete'] = False
            value['sources'][0]['lines'][0]['state'] = 'unknown'
        else:
            value['executionStatus'] = value['status'] = 'failed'
        report = copied/'coverage.json'
        report.unlink()
        report.write_text(json.dumps(value, sort_keys=True, separators=(',', ':')))
        exported = invoke(label, report=report, expected=3 if label.startswith('unknown') else 1, xml=originals(single))
        assert exported['sourceStatus'] == exported['status'] == value['status'] and exported['complete']

    for label, selection in [('missing-target', ('--all-modules',)),
                             ('absent-target', ('--all-modules', '--target', 'absent')),
                             ('absent-module', ('--module', 'absent', '--target', 'jvm')),
                             ('missing-module-selection', ('--target', 'jvm'))]:
        invoke(label, selection=selection, expected=2, refusal=True)
    corrupted = output/'copied-corrupt-aggregate'
    shutil.copytree(reports['combined'].parent, corrupted)
    contributor = json.loads(reports['combined'].read_text())['contributors'][0]
    xml_entry = next(entry for entry in contributor['report']['originals']['files'] if entry['path'].endswith('/jacoco.xml'))
    original = corrupted/'contributors'/contributor['digest']['hash']/xml_entry['path']
    original.unlink()
    original.write_bytes(b'<report name="corrupted"/>')
    diagnostic = invoke('corrupt-original', report=corrupted/'aggregate.json', expected=3, refusal=True)
    assert 'differs' in diagnostic['message'] or 'digest' in diagnostic['message']
    assert snapshot(paths) == before, 'Export changed original input, effect, report bytes or mtimes'
    assert sorted(str(path) for path in source_case.glob('*/runs/*/verification.json')) == runs_before
    print('PASS JaCoCo XML export: exact original bytes, per-check separation, selection and status/refusal semantics; no test rerun', flush=True)


if __name__ == '__main__':
    main()
