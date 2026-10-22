#!/usr/bin/env python3
"""Actual guardian startup errors versus executable exits, strict frames and Native decoding.

The fault guardian is a separately compiled test-only syscall/report wrapper. The
production helper contains no fault-injection environment switch. No consumers run.
"""
import argparse
import errno
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.launcher import run_owned


def digest(path):
    data = path.read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-build', action='store_true')
    parser.add_argument('--c-only', action='store_true')
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/process-setup'; output.mkdir(parents=True, exist_ok=True)
    platform = ROOT/'execution/platform'; fixture = ROOT/'tests/process-setup'
    native = Path.home()/'.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20'
    java = Path.home()/'.sdkman/candidates/java/25.0.4-librca'
    developer = Path('/Applications/Xcode.app/Contents/Developer')
    clang = developer/'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang'; ar = clang.with_name('ar')
    sdk = developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk'
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), JAVA_HOME=str(java),
        KONAN_DATA_DIR=str(Path.home()/'.konan'), DEVELOPER_DIR=str(developer), SDKROOT=str(sdk),
        TMPDIR=tempfile.gettempdir(), LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_COMPILER_SERVER='false', KONAN_USE_INTERNAL_SERVER='0')
    sources = sorted((ROOT/'core/src').rglob('*.kt')) + sorted((ROOT/'execution/src').rglob('*.kt')) + [fixture/'SetupProbe.kt']
    flags = [clang, '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-I'+str(platform)]
    commands = [
        [*flags, platform/'guardian.c', '-o', output/'guardian'],
        [*flags, fixture/'fault_guardian.c', '-o', output/'fault-guardian'],
        [*flags, fixture/'frame_guardian.c', '-o', output/'frame-guardian'],
        [*flags, '-c', platform/'process.c', '-o', output/'process.o'],
        [ar, 'rcs', output/'libkvik-process.a', output/'process.o'],
        [*flags, fixture/'probe.c', output/'libkvik-process.a', '-o', output/'probe'],
    ]
    binaries = ['guardian', 'fault-guardian', 'frame-guardian', 'probe']
    if not args.c_only:
        commands += [
            [native/'bin/cinterop', '-def', platform/'process.def', '-compiler-option', '-I'+str(platform),
             '-libraryPath', output, '-target', 'macos_arm64', '-Xoverride-konan-properties', 'airplaneMode=true', '-o', output/'process'],
            [native/'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true', '-library', output/'process.klib',
             *sources, '-entry', 'kvik.execution.setupprobe.main', '-o', output/'native.kexe'],
        ]
        binaries += ['native.kexe', 'process.klib']
    commands = [list(map(str, command)) for command in commands]
    tracked = [platform/name for name in ('guardian.c','process.c','process.h','process.def')] + sorted(fixture.iterdir())
    tracked += [Path(__file__).resolve(), *sorted((ROOT/'bootstrap').glob('*.py'))]
    if not args.c_only: tracked += sources
    tools = [clang, ar, java/'bin/java', java/'release', native/'bin/konanc', native/'bin/cinterop', native/'bin/run_konan',
             native/'tools/env_blacklist', *sorted((native/'konan/lib').glob('*.jar'))]
    def stamp():
        return dict(commands=commands, environment=environment, files={str(p): digest(p) for p in tracked+tools})
    inputs = stamp(); receipt = output/('c-build.json' if args.c_only else 'build.json')
    log_path = output/('c-compile.log' if args.c_only else 'compile.log')
    if args.no_build:
        old = json.loads(receipt.read_text())
        assert old['inputs'] == inputs and old['binaries'] == {n:digest(output/n) for n in binaries}
        assert old['compileLog'] == digest(log_path)
    else:
        with log_path.open('wb') as log:
            for command in commands: run_owned(command, environment, log, timeout=300)
        assert stamp() == inputs, 'Compiler inputs changed'
        write(receipt, dict(inputs=inputs, binaries={n:digest(output/n) for n in binaries}, compileLog=digest(log_path)))
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    print('Evidence:', case, flush=True)
    for name in binaries: shutil.copy2(output/name, case/name)
    shutil.copy2(receipt, case/'build.json'); shutil.copy2(log_path, case/'compile.log')
    summary = dict(passed=False, build=digest(case/'build.json'), cases=[])
    write(case/'summary.json', summary)
    ordinary = [('normal',0,0,0),('exit125',125,0,0),('exit127',127,0,0),('pause',0,0,0),('signal',143,0,0),
                ('cwd',125,17,errno.ENOENT),('exec',127,24,errno.ENOENT),
                ('stdout',125,19,errno.EEXIST),('stderr',125,20,errno.EEXIST)]
    injected = [('fork',125,13,errno.EAGAIN),('parent-group',125,14,errno.EPERM),('child-group',125,15,errno.EPERM),
                ('cancel',130,17,errno.ENOENT),('timeout',124,17,errno.ENOENT)]
    malformed_status = ['partial-status','unknown-status','extra-status']
    malformed_frames = ['unknown-frame','zero-error','negative-error','orphan-error','success-with-error','exec-wrong-code',
                        'cancel-wrong-code','both-flags','old-frame','partial-frame','extra-frame','empty-frame']
    for helper, cases in [('guardian',ordinary),('fault-guardian',injected),
                          ('fault-guardian',[(n,None,None,None) for n in malformed_status]),
                          ('frame-guardian',[(n,None,None,None) for n in malformed_frames])]:
        for mode,code,stage,error in cases:
            directory = case/mode; directory.mkdir()
            if mode in ('stdout','stderr'): (directory/mode).write_bytes(b'preserve')
            command = [str(case/'probe'),str(case/helper),str(directory),mode]
            write(directory/'command.json', dict(arguments=command,environment=environment))
            started = time.monotonic()
            result = subprocess.run(command,env=environment,capture_output=True,timeout=15)
            (directory/'probe.stdout').write_bytes(result.stdout);(directory/'probe.stderr').write_bytes(result.stderr)
            assert result.returncode == 0,(mode,result.returncode,result.stderr)
            value = json.loads(result.stdout)
            if code is None:
                assert value['poll']==-1 and value['close']==-1 and value['setup']==-1,(mode,value)
            else:
                assert value == dict(poll=1,exitCode=code,timedOut=int(mode=='timeout'),cancelled=int(mode=='cancel'),
                    setup=int(stage!=0),stage=stage,errno=error,close=0),(mode,value)
                if mode in ('stdout','stderr'): assert (directory/mode).read_bytes()==b'preserve'
            summary['cases'].append(dict(mode=mode,result=value,seconds=time.monotonic()-started))
            write(case/'summary.json',summary)
    if not args.c_only:
        directory=case/'native';directory.mkdir();command=[str(case/'native.kexe'),str(case/'guardian'),str(directory)]
        write(directory/'command.json',dict(arguments=command,environment=environment))
        result=subprocess.run(command,env=environment,capture_output=True,timeout=30)
        (directory/'stdout.json').write_bytes(result.stdout);(directory/'stderr').write_bytes(result.stderr)
        assert result.returncode==0,result.stderr
        summary['native']=json.loads(result.stdout);assert len(summary['native'])==4
    assert stamp()==inputs
    summary['passed']=True;write(case/'summary.json',summary)
    print('PASS',len(summary['cases']),'C cases; Native=',not args.c_only,flush=True)


if __name__=='__main__':main()
