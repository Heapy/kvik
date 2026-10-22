#!/usr/bin/env python3
"""Actual JaCoCo data and original classes; coverage reporting never invokes tests."""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from test_jvm import acquire, canonical, digest

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--jacoco-artifacts', type=Path, required=True)
    parser.add_argument('--jacoco-lock', type=Path, default=ROOT/'bootstrap/jvm-artifacts.lock.json')
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/jvm-coverage-worker'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    env = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': os.environ['HOME'], 'JAVA_HOME': str(args.java_home),
           'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC'}

    def run(label, command, expected=0):
        print(label, flush=True)
        with (case/(label+'.stdout')).open('wb') as out, (case/(label+'.stderr')).open('wb') as err:
            result = subprocess.run(list(map(str, command)), env=env, stdout=out, stderr=err, timeout=180)
        assert result.returncode == expected, (label, result.returncode, (case/(label+'.stderr')).read_text())

    compiler = acquire(json.loads((ROOT/'experiments/k01/artifacts.lock.json').read_text()), case/'compiler', args.artifacts, True)
    lock = json.loads(args.jacoco_lock.read_text())
    jacoco = acquire({'artifacts': [lock['jacocoAgent'], lock['jacocoCli']]}, case/'jacoco', args.jacoco_artifacts, True)
    cli = jacoco[lock['jacocoCli']['coordinate']]
    agent = jacoco[lock['jacocoAgent']['coordinate']]
    stdlib = next(path for coordinate, path in compiler.items() if coordinate.split(':')[1] == 'kotlin-stdlib')
    java, javac = args.java_home/'bin/java', args.java_home/'bin/javac'
    worker = case/'worker'
    run('compile-worker', [java, '-cp', os.pathsep.join(map(str, compiler.values())), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', os.pathsep.join(map(str, [*compiler.values(), cli])),
        '-d', worker, *sorted((ROOT/'core/src').rglob('*.kt')), ROOT/'compiler/jvm/src/kvik/jvm/WorkerFiles.kt',
        ROOT/'compiler/jvm/src/kvik/jvm/CoverageWorker.kt'])
    fixture = ROOT/'tests/jvm-coverage/fixture'
    seed = case/'seed'
    classes = seed/'classes/app'
    sources = seed/'coverage-sources/src'
    sources.mkdir(parents=True)
    for name in ('Production.java', 'Unexecuted.java'):
        shutil.copyfile(fixture/name, sources/name)
    run('compile-production', [javac, '-g', '-d', classes, sources/'Production.java', sources/'Unexecuted.java'])
    run('compile-driver', [javac, '-g', '-cp', classes, '-d', case/'driver', fixture/'Main.java'])
    run('collect-data', [java, f'-javaagent:{agent}=destfile={seed}/coverage.exec,append=false,includes=fixture.*',
                         '-cp', os.pathsep.join(map(str, [case/'driver', classes])), 'fixture.Main'])

    def entry(path, workspace):
        return dict(path=path.relative_to(workspace).as_posix(), digest=digest(path), executable=False)

    def specification(workspace):
        return dict(engine='jacoco', version='0.8.14', agent=digest(agent), reporter=digest(cli), excludes=[],
            classDirectories=[dict(module='app', path='classes/app')], sources=[dict(module='app', sourceSet='main', path='src/'+name,
                digest=digest(workspace/'coverage-sources/src'/name)) for name in ('Production.java', 'Unexecuted.java')])

    def invoke(label, mutate=None, expected=0):
        workspace = case/label
        shutil.copytree(seed, workspace)
        spec = specification(workspace)
        request = dict(schemaVersion=1, requestId=label, specification=spec, executionComplete=True,
                       executionData=[entry(workspace/'coverage.exec', workspace)],
                       files=[entry(p, workspace) for p in sorted((workspace/'classes').rglob('*.class'))])
        if mutate:
            mutate(workspace, request)
        (workspace/'request.json').write_bytes(canonical(request))
        run(label, [java, '-cp', os.pathsep.join(map(str, [worker, cli, stdlib])), 'kvik.jvm.CoverageWorker',
                    workspace, 'request.json', 'result.json'], expected)
        result = json.loads((workspace/'result.json').read_text())
        assert result['requestId'] == label
        for artifact in result['xml']:
            assert digest(workspace/artifact['path']) == artifact['digest']
            root = ET.parse(workspace/artifact['path']).getroot()
            assert root.tag == 'report' and root.attrib['name'] == artifact['module']
            assert any(counter.attrib['type'] == 'METHOD' for counter in root.findall('counter'))
        for source in result['sources']:
            assert [line['number'] for line in source['lines']] == list(range(1, source['lineCount']+1))
        return result

    measured = invoke('measured')
    assert measured['status'] == 'complete', measured
    report = {source['source']['path']: source for source in measured['sources']}
    production = report['src/Production.java']
    states = {line['number']: line['state'] for line in production['lines']}
    assert states[6] == 'covered' and states[8] == states[12] == 'uncovered', states
    assert states[1] == states[2] == 'non_executable'
    assert production['lineCount'] == len((fixture/'Production.java').read_text().splitlines())
    assert 'covered' not in {line['state'] for line in report['src/Unexecuted.java']['lines']}
    assert any(line['state'] == 'uncovered' for line in report['src/Unexecuted.java']['lines'])

    incomplete = invoke('interrupted', lambda w, r: r.update(executionComplete=False))
    assert incomplete['status'] == 'incomplete' and all(line['state'] == 'unknown' for s in incomplete['sources'] for line in s['lines'])
    missing_data = invoke('missing-data', lambda w, r: r.update(executionComplete=False, executionData=[]))
    assert missing_data['status'] == 'incomplete' and all(line['state'] == 'unknown' for s in missing_data['sources'] for line in s['lines'])
    def ambiguous(w, r):
        other = w/'coverage-sources/other/Production.java'; other.parent.mkdir(); shutil.copyfile(w/'coverage-sources/src/Production.java', other)
        r['specification']['sources'].append(dict(module='app', sourceSet='main', path='other/Production.java', digest=digest(other)))
    duplicate = invoke('ambiguous', ambiguous)
    assert duplicate['status'] == 'incomplete'
    assert all(not s['complete'] and all(line['state'] == 'unknown' for line in s['lines']) for s in duplicate['sources'] if s['source']['path'].endswith('Production.java'))
    def missing(w, r):
        path = w/'coverage-sources/src/NoClasses.java'; path.write_text('// no associated class\n')
        r['specification']['sources'].append(dict(module='app', sourceSet='main', path='src/NoClasses.java', digest=digest(path)))
    assert invoke('unmatched', missing)['sources'][-1]['lines'] == [dict(number=1, state='unknown')]

    for label, options in [('no-debug', ['-g:none']), ('no-lines', ['-g:source,vars'])]:
        alternate = case/(label+'-classes')
        run('compile-'+label, [javac, *options, '-d', alternate, sources/'Production.java', sources/'Unexecuted.java'])
        def replace_classes(w, r):
            shutil.rmtree(w/'classes/app'); shutil.copytree(alternate, w/'classes/app')
            r['files'] = [entry(p, w) for p in sorted((w/'classes').rglob('*.class'))]
        result = invoke(label, replace_classes)
        assert result['status'] == 'incomplete' and all(not s['complete'] for s in result['sources'])
        assert any('debug information' in diagnostic for s in result['sources'] for diagnostic in s['diagnostics'])

    def second_module(w, r):
        shutil.copytree(w/'classes/app', w/'classes/other')
        r['specification']['classDirectories'].append(dict(module='other', path='classes/other'))
        r['specification']['sources'] += [dict(s, module='other') for s in r['specification']['sources']]
        r['files'] = [entry(p, w) for p in sorted((w/'classes').rglob('*.class'))]
    assert invoke('module-isolation', second_module)['status'] == 'complete'
    def duplicate_class(w, r):
        p = w/'classes/app/Duplicate.class'; shutil.copyfile(w/'classes/app/fixture/Production.class', p); r['files'].append(entry(p, w))
    assert invoke('duplicate-class', duplicate_class, 1)['status'] == 'incomplete'
    def source_corruption(w, r):
        p = w/'coverage-sources/src/Production.java'; p.write_bytes(p.read_bytes().replace(b'return 7', b'return 8'))
    assert invoke('source-corruption', source_corruption, 1)['status'] == 'incomplete'
    def bad_exec(w, r):
        p = w/'coverage.exec'; p.write_bytes(b'bad'); r['executionData'] = [entry(p, w)]
    assert invoke('malformed-exec', bad_exec, 1)['status'] == 'incomplete'
    def short_source(w, r):
        p = w/'coverage-sources/src/Production.java'; p.write_text('short\n'); r['specification']['sources'][0]['digest'] = digest(p)
    assert invoke('invalid-lines', short_source, 1)['status'] == 'incomplete'
    assert invoke('unsupported-excludes', lambda w, r: r['specification'].update(excludes=['fixture.*']), 1)['status'] == 'incomplete'
    print(f'PASS coverage worker scenarios ({case})', flush=True)


if __name__ == '__main__':
    main()
