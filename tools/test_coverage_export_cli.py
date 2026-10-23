#!/usr/bin/env python3
"""Export retained coverage to Sonar Generic XML without capturing or running tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
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


def expected_lines(sources, module=None):
    result = {}
    for source in sources:
        owners = source.get('owners', [source.get('source')])
        if module is not None and not any(owner['module'] == module for owner in owners):
            continue
        path = source.get('path') or source['source']['path']
        assert source['complete'] and path not in result
        assert all(line['state'] != 'unknown' for line in source['lines'])
        result[path] = {line['number']: line['state'] == 'covered' for line in source['lines']
                        if line['state'] in ('covered', 'uncovered')}
    return result


def check_xml(path, expected):
    # Official Sonar Generic coverage v1 shape; this conversion intentionally has no branch attributes.
    raw = path.read_bytes()
    assert b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw
    root = ET.fromstring(raw)
    assert root.tag == 'coverage' and root.attrib == {'version': '1'}
    actual = {}
    for file in root:
        assert file.tag == 'file' and set(file.attrib) == {'path'}
        name = file.attrib['path']
        logical = PurePosixPath(name)
        assert not logical.is_absolute() and str(logical) == name and '..' not in logical.parts and '\\' not in name
        assert name not in actual
        lines = actual[name] = {}
        for line in file:
            assert line.tag == 'lineToCover' and set(line.attrib) == {'lineNumber', 'covered'} and len(line) == 0
            number = int(line.attrib['lineNumber'])
            assert number > 0 and str(number) == line.attrib['lineNumber'] and number not in lines
            assert line.attrib['covered'] in ('true', 'false')
            lines[number] = line.attrib['covered'] == 'true'
    assert actual == expected, (actual, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--case', type=Path, default=DEFAULT_CASE)
    parser.add_argument('--single-report', type=Path, default=DEFAULT_SINGLE)
    parser.add_argument('--schema-root', type=Path)
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/coverage-export-cli')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    source_case = args.case.resolve(strict=True)
    single = args.single_report.resolve(strict=True)
    reports = {name: source_case/'aggregates'/name/'aggregate.json' for name in ('combined', 'missing', 'unsupported-native')}
    values = {name: json.loads(path.read_text()) for name, path in reports.items()}
    single_value = json.loads(single.read_text())
    measured, = values['combined']['targets']
    assert measured['target'] == 'jvm' and measured['complete'] and measured['covered'] < measured['executable']
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
        command = [str(runtime), 'coverage-export', '--report', str(report), '--format', 'sonar-generic',
                   *selection, '--output', str(destination)]
        (output/(label+'.command.json')).write_text(json.dumps(command))
        with (output/(label+'.stdout.json')).open('wb') as stdout, (output/(label+'.stderr.log')).open('wb') as stderr:
            process = subprocess.run(command, env=environment, stdout=stdout, stderr=stderr, timeout=120)
        text = (output/(label+'.stdout.json')).read_text()
        assert process.returncode == expected, (label, process.returncode, text, (output/(label+'.stderr.log')).read_text())
        response = json.loads(text)
        if refusal:
            assert not destination.exists(), (label, 'Refused command published output')
            assert response['diagnostic']['code'] and response['diagnostic']['message']
            print('PASS '+label, flush=True)
            return response['diagnostic']
        validate(response, 'coverageConsumerExport')
        metadata = response['result']
        validate(metadata, 'coverageConsumerExportResult')
        assert metadata['kind'] == 'coverage-consumer-export' and metadata['format'] == 'sonar-generic'
        assert metadata['report'] == digest(report)
        assert response['outputDirectory'] == str(destination)
        assert json.loads((destination/'report/export.json').read_text()) == metadata
        assert metadata['conversionLosses'], 'LINE conversion must disclose unsupported richer metrics'
        assert metadata['complete'] == (xml is not None)
        assert (destination/'report/coverage.xml').exists() == (xml is not None)
        if xml is not None:
            assert metadata['outcome'] == 'exported'
            check_xml(destination/'report/coverage.xml', xml)
            entry, = metadata['files']['files']
            assert entry == dict(path='coverage.xml', executable=False, digest=digest(destination/'report/coverage.xml'))
        else:
            assert metadata['outcome'] in ('incomplete', 'unsupported') and metadata['files']['files'] == []
        artifacts = response['artifacts']['files']
        assert len({entry['path'] for entry in artifacts}) == len(artifacts)
        for entry in artifacts:
            assert digest(destination/entry['path']) == entry['digest'] and not entry['executable']
        assert {p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()} == {
            entry['path'] for entry in artifacts}
        graph = json.loads((destination/'graph.json').read_text())
        validate(graph, 'coverageConsumerExportGraph')
        assert graph['sourceReport'] == digest(report)
        action, = graph['actions']
        result, = graph['results']
        assert action['action']['kind'] == 'generate' and action['action']['cachePolicy'] == 'never'
        assert action['action']['outputs'] == ['report'] and action['dependencies'] == []
        assert result['status'] == 'executed' and action['actionId'] == result['actionId']
        assert {entry['path'] for entry in result['outputs']['files']} == {
            'report/export.json', *(['report/coverage.xml'] if xml is not None else [])}
        for entry in result['outputs']['files']:
            assert digest(destination/entry['path']) == entry['digest']
        print('PASS '+label, flush=True)
        return metadata

    union = expected_lines(measured['sources'])
    assert len(union) < sum(len(c['report']['sources']) for c in values['combined']['contributors'])
    combined = invoke('combined', xml=union)
    assert combined['sourceStatus'] == combined['status'] == 'passed' and combined['target'] == 'jvm'
    assert combined['modules'] == sorted({owner['module'] for source in measured['sources'] for owner in source['owners']})
    assert sum(sum(lines.values()) for lines in union.values()) == measured['covered']
    assert sum(len(lines) for lines in union.values()) == measured['executable']
    library = invoke('library', selection=('--module', 'library', '--target', 'jvm'), xml=expected_lines(measured['sources'], 'library'))
    assert library['modules'] == ['library']
    default = invoke('single-default-target', report=single, selection=('--all-modules',), xml=expected_lines(single_value['sources']))
    assert default['target'] == 'jvm' and default['status'] == 'passed'
    missing = invoke('missing', report=reports['missing'], expected=3)
    assert missing['sourceStatus'] == missing['status'] == missing['outcome'] == 'incomplete'
    native = invoke('unsupported-native', report=reports['unsupported-native'], selection=('--all-modules', '--target', 'macosArm64'), expected=3)
    assert native['outcome'] == 'unsupported' and native['status'] == 'incomplete'
    jvm = invoke('jvm-with-missing-native', report=reports['unsupported-native'], expected=3, xml=union)
    assert jvm['sourceStatus'] == jvm['status'] == 'incomplete' and jvm['complete']

    # This copied metadata case tests status preservation, not an additional failed test execution.
    failed = output/'copied-failed-single'
    shutil.copytree(single.parent, failed)
    failed_value = dict(single_value, executionStatus='failed', status='failed')
    (failed/'coverage.json').unlink()
    (failed/'coverage.json').write_text(json.dumps(failed_value, sort_keys=True, separators=(',', ':')))
    failed_export = invoke('failed-source', report=failed/'coverage.json', expected=1, xml=expected_lines(single_value['sources']))
    assert failed_export['sourceStatus'] == failed_export['status'] == 'failed'

    for label, selection in [('missing-target', ('--all-modules',)),
                             ('absent-target', ('--all-modules', '--target', 'absent')),
                             ('absent-module', ('--module', 'absent', '--target', 'jvm')),
                             ('missing-module-selection', ('--target', 'jvm'))]:
        diagnostic = invoke(label, selection=selection, expected=2, refusal=True)
        assert diagnostic['code'] == 'KVIK_COVERAGE_EXPORT_ARGUMENTS'
    copied = output/'copied-corrupt-aggregate'
    shutil.copytree(reports['combined'].parent, copied)
    contributor = values['combined']['contributors'][0]
    original = copied/'contributors'/contributor['digest']['hash']/'coverage.exec'
    original.unlink()
    original.write_bytes(b'corrupt copied original')
    diagnostic = invoke('corrupt-original', report=copied/'aggregate.json', expected=3, refusal=True)
    assert diagnostic['code'] == 'KVIK_COVERAGE_EXPORT_FAILED'
    assert 'digest' in diagnostic['message'] or 'differs' in diagnostic['message']
    assert snapshot(paths) == before, 'Export changed original input, effect, report bytes or mtimes'
    assert sorted(str(path) for path in source_case.glob('*/runs/*/verification.json')) == runs_before
    print('PASS Sonar Generic export: exact lines, dedup, selection, status/refusal semantics; no test rerun', flush=True)


if __name__ == '__main__':
    main()
