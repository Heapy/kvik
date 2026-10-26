"""Consumer-only edits must remain visible without rebuilding the native runtime."""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import digest, file_digest, read_json
from cached_startup import cache_event, invoke, launcher_files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher-root', type=Path, default=ROOT)
    parser.add_argument('--cache-dir', type=Path)
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/bootstrap-consumer-reuse')
    args = parser.parse_args()
    root = args.launcher_root.resolve(strict=True)
    cache = (args.cache_dir or root/'.kvik/runtime').resolve(strict=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    project = case/'project'; project.mkdir()
    source = project/'src/Main.kt'; source.parent.mkdir()
    source.write_text('package fixture\nfun value() = 1\n')
    config = project/'kvik.json'
    document = dict(schemaVersion=1, name='consumer-reuse',
        repositories=[dict(id='fixture', url='https://fixture.invalid/maven')], modules=[dict(
        id='library', sourceSets=[dict(name='main', directories=['src'], resources=[],
            dependsOn=[], dependencies=[])], targets=[dict(name='jvm', main='main', test=None,
                compilerOptions=['-jvm-target=17'])], compilerPlugins=[])])
    config.write_text(json.dumps(document)+'\n')
    original_source, original_config = source.read_bytes(), config.read_bytes()
    missing = {name: case/'absent'/name for name in ('jdk', 'native', 'native-data', 'artifacts')}
    command = [sys.executable, root/'kvik', '--cache-dir', cache, '--offline', '--no-build',
        '--java-home', missing['jdk'], '--konan-home', missing['native'],
        '--konan-data', missing['native-data'], '--artifact-cache', missing['artifacts']]
    environment = {name: os.environ[name] for name in ('HOME', 'TMPDIR') if name in os.environ}
    environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='C', LC_ALL='C', TZ='UTC')
    summary = dict(schemaVersion=1, passed=False, launcherFiles=launcher_files(root), records=[],
        harness=[dict(path=str(path), digest=file_digest(path)) for path in
                 (Path(__file__), Path(__file__).with_name('cached_startup.py'), ROOT/'bootstrap/identity.py')],
        scope='Local source composition; real cached launcher and Native inspection, no consumer compilation')

    def save():
        (case/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')

    def run(label, arguments):
        record, stdout, stderr = invoke([*command, *arguments], cwd=project,
            environment=environment, timeout=20)
        record.update(label=label, source=file_digest(source), configuration=file_digest(config))
        for role, data in (('stdout', stdout), ('stderr', stderr)):
            name = label+'.'+role
            (case/name).write_bytes(data)
            record[role] = name
            record[role+'Digest'] = digest(data)
        summary['records'].append(record); save()
        assert record['exitCode'] == 0 and not record['timedOut'], record
        return stdout, stderr

    try:
        stdout, stderr = run('runtime', ['--print-runtime'])
        executable = Path(stdout.decode().strip()).resolve(strict=True)
        manifest = read_json(executable.parent/'manifest.json')
        composition = manifest['composition']
        assert executable == cache/'executables'/composition['hash']/'kvik.kexe'
        cache_event(stderr, composition['hash'])
        summary['runtime'] = dict(path=str(executable), composition=composition,
            executable=file_digest(executable), manifest=file_digest(executable.parent/'manifest.json'))
        assert summary['runtime']['executable'] == manifest['executable']

        def inspect(label, expected_options):
            stdout, stderr = run(label, ['--', 'inspect', '--project', config])
            cache_event(stderr, composition['hash'])
            value = json.loads(stdout)
            assert value['composition'] == composition
            assert value['project']['modules'][0]['targets'][0]['compilerOptions'] == expected_options
            assert {item['path'] for item in value['files']} == {'src/Main.kt'}
            file, = value['files']
            assert (file['modules'], file['sourceSets'], file['targets']) == (['library'], ['main'], ['jvm'])
            assert value['settings']['target:library:jvm:main:compilerOptions'] == dict(
                value=expected_options, source='configuration', origins=[dict(
                    file='kvik.json', pointer='/modules/0/targets/0/compilerOptions')])
            return value

        baseline = inspect('baseline', ['-jvm-target=17'])
        source.write_bytes(original_source.replace(b'= 1', b'= 2'))
        assert source.read_bytes() != original_source and config.read_bytes() == original_config
        changed_source = inspect('source-edit', ['-jvm-target=17'])
        assert changed_source['configuration'] == baseline['configuration']
        source.write_bytes(original_source)
        document['modules'][0]['targets'][0]['compilerOptions'] = ['-jvm-target=21']
        config.write_text(json.dumps(document)+'\n')
        assert config.read_bytes() != original_config and source.read_bytes() == original_source
        changed_config = inspect('configuration-edit', ['-jvm-target=21'])
        assert changed_config['configuration'] != baseline['configuration']
        config.write_bytes(original_config)
        restored = inspect('restored', ['-jvm-target=17'])
        assert restored == baseline
        assert file_digest(executable) == summary['runtime']['executable']
        assert file_digest(executable.parent/'manifest.json') == summary['runtime']['manifest']
        assert launcher_files(root) == summary['launcherFiles']
        assert not any(path.exists() or path.is_symlink() for path in missing.values())
        summary['passed'] = True
    except BaseException as error:
        summary['failure'] = dict(type=type(error).__name__, message=str(error))
        raise
    finally:
        source.write_bytes(original_source); config.write_bytes(original_config)
        save()
    print(json.dumps(dict(passed=True, summary=str(case/'summary.json'))), flush=True)


if __name__ == '__main__':
    main()
