#!/usr/bin/env python3
"""Compile real hierarchical expect/actual sources using NativeCompilation's emitted arguments."""
import argparse
import json
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.source_process import OwnedProcessRequest, run_owned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/native-refinement'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    print('CASE', case, flush=True)
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(args.java_home.resolve()),
        HOME=str(Path.home()), KONAN_DATA_DIR=str(Path.home()/'.konan'), TMPDIR=tempfile.gettempdir(),
        DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer', LANG='C.UTF-8', TZ='UTC', KONAN_USE_INTERNAL_SERVER='0')
    compiler = args.konan_home.resolve()/'bin/konanc'
    def run(label, arguments, cwd=case, success=True):
        result = run_owned(OwnedProcessRequest(tuple(map(str, arguments)), cwd, environment, timeout_seconds=120,
            stdout_limit=1024*1024, stderr_limit=1024*1024))
        (case/(label+'.stdout')).write_bytes(result.stdout)
        (case/(label+'.stderr')).write_bytes(result.stderr)
        assert result.outcome == 'exited' and (result.exit_code == 0) == success, (label, result)
        print('PASS', label, flush=True)
        return result
    sources = sorted((ROOT/'core/src').rglob('*.kt'))
    run('compile-unit', [compiler, '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
        '-generate-test-runner', '-o', case/'unit.kexe', *sources,
        ROOT/'compiler/native/src/kvik/compiler/native/NativeActions.kt', ROOT/'tests/native/NativeFragmentTest.kt'])
    unit = run('unit', [case/'unit.kexe'])
    plans = [json.loads(line.split('=', 1)[1]) for line in unit.stdout.decode().splitlines() if line.startswith('KVIK_NATIVE_FRAGMENT_PLAN=')]
    assert len(plans) == 2, unit.stdout
    for plan in plans:
        work = case/plan['target']; (work/'input').mkdir(parents=True); (work/'output').mkdir()
        (work/'input/Common.kt').write_text('package fixture\nexpect fun platformName(): String\n')
        (work/'input/Native.kt').write_text('package fixture\nactual fun platformName(): String = "native"\n')
        run(plan['target'], [compiler, *plan['arguments']], cwd=work)
        assert (work/'output/fixture.klib').stat().st_size > 0
    siblings = [json.loads(line.split('=', 1)[1]) for line in unit.stdout.decode().splitlines() if line.startswith('KVIK_NATIVE_SIBLING_PLAN=')]
    assert len(siblings) == 1
    work = case/'sibling-tests'; (work/'input').mkdir(parents=True); (work/'output').mkdir()
    (work/'input/Main.kt').write_text('package fixture\ninternal fun mainInternal() = "value"\n')
    (work/'input/Test.kt').write_text('package fixture\nfun testAccess() = mainInternal()\n')
    run('sibling-tests', [compiler, *siblings[0]], cwd=work)
    plan = plans[0]
    flat = [value for value in plan['arguments'] if not value.startswith('-Xfragment')]
    flat += ['-Xcommon-sources=input/Common.kt,input/Native.kt']
    failed = run('flattened-control', [compiler, *flat], cwd=case/plan['target'], success=False)
    assert b'same module' in failed.stderr, failed.stderr
    print('PASS 5 unit groups, 2 actual iOS hierarchies with empty structural/terminal fragments, sibling test visibility, flattened negative control', flush=True)


if __name__ == '__main__':
    main()
