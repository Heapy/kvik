#!/usr/bin/env python3
"""Focused real Native error propagation and all-sibling cleanup; immutable build receipt."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import select
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from bootstrap.launcher import run_owned


def digest(path):
    value=path.read_bytes();return dict(hash=hashlib.sha256(value).hexdigest(),sizeBytes=len(value))


def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--no-build',action='store_true');args=parser.parse_args()
    output=ROOT/'.kvik/tests/nested-ownership-native';output.mkdir(parents=True,exist_ok=True)
    native=Path.home()/'.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20';java=Path.home()/'.sdkman/candidates/java/25.0.4-librca'
    developer=Path('/Applications/Xcode.app/Contents/Developer');clang=developer/'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang';ar=clang.with_name('ar')
    sdk=developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk';platform=ROOT/'execution/platform'
    environment=dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin',JAVA_HOME=str(java),HOME=str(Path.home()),KONAN_DATA_DIR=str(Path.home()/'.konan'),
        DEVELOPER_DIR=str(developer),SDKROOT=str(sdk),TMPDIR=tempfile.gettempdir(),LANG='C',LC_ALL='C',TZ='UTC',KONAN_USE_COMPILER_SERVER='false',KONAN_USE_INTERNAL_SERVER='0')
    sources=sorted((ROOT/'core/src').rglob('*.kt'))+sorted((ROOT/'execution/src').rglob('*.kt'))+[ROOT/'tests/nested-ownership/ProtocolFailureProbe.kt']
    flags=[clang,'-std=c11','-D_DARWIN_C_SOURCE','-Wall','-Wextra','-Werror']
    commands=[[*flags,platform/'guardian.c','-o',output/'guardian'],[*flags,ROOT/'tests/nested-ownership/select_guardian.c','-o',output/'select-guardian'],
        [*flags,'-c',platform/'process.c','-o',output/'process.o'],[ar,'rcs',output/'libkvik-process.a',output/'process.o'],
        [native/'bin/cinterop','-def',platform/'process.def','-compiler-option','-I'+str(platform),'-libraryPath',output,'-target','macos_arm64','-Xoverride-konan-properties','airplaneMode=true','-o',output/'process'],
        [native/'bin/konanc','-target','macos_arm64','-Xoverride-konan-properties=airplaneMode=true','-library',output/'process.klib',*sources,'-entry','kvik.execution.nestedfailure.main','-o',output/'probe.kexe']]
    commands=[list(map(str,c)) for c in commands]
    source_paths=sources+[platform/n for n in ('process.c','process.h','process.def','guardian.c')]+[ROOT/'tests/nested-ownership/select_guardian.c']
    support=[Path(__file__).resolve(),*sorted((ROOT/'bootstrap').glob('*.py'))]
    tools=[java/'bin/java',native/'bin/konanc',native/'bin/cinterop',native/'bin/run_konan',native/'tools/env_blacklist',clang,ar,*sorted((native/'konan/lib').glob('*.jar'))]
    def stamp():return dict(commands=commands,environment=environment,sources={str(p.relative_to(ROOT)):digest(p) for p in source_paths},support={str(p.relative_to(ROOT)):digest(p) for p in support},tools={str(p):digest(p) for p in tools})
    inputs=stamp();binaries=['guardian','select-guardian','probe.kexe'];receipt=output/'build.json';compile_log=output/'compile.log'
    if args.no_build:
        old=json.loads(receipt.read_bytes());assert old['inputs']==inputs and old['binaries']=={n:digest(output/n) for n in binaries} and old['compileLog']==digest(compile_log)
    else:
        with compile_log.open('wb') as log:
            for command in commands:run_owned(command,environment,log,timeout=300)
        assert stamp()==inputs
        write(receipt,dict(inputs=inputs,binaries={n:digest(output/n) for n in binaries},compileLog=digest(compile_log)))
    case=Path(tempfile.mkdtemp(prefix='case-',dir=output)).resolve();print('Evidence:',case,flush=True)
    for name in [*binaries,'build.json','compile.log']:shutil.copy2(output/name,case/name)
    def table():
        result=subprocess.run(['/bin/ps','-axo','pid=,ppid=,pgid=,stat=,command='],capture_output=True,text=True,check=True,timeout=5)
        return {int(p):dict(parent=int(pp),group=int(g),state=s,command=c) for p,pp,g,s,c in (line.split(None,4) for line in result.stdout.splitlines())}
    table();command=list(map(str,[case/'probe.kexe',case/'guardian',case,case/'select-guardian']));write(case/'command.json',command)
    owner=subprocess.Popen(command,env=environment,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    captured={};prefix=b''
    try:
        assert select.select([owner.stdout],[],[],15)[0],'Native readiness missing'
        prefix=owner.stdout.readline();assert prefix==b'READY\n',prefix
        rows=table();ids={owner.pid}
        while True:
            more=ids|{pid for pid,r in rows.items() if r['parent'] in ids}
            if more==ids:break
            ids=more
        captured={pid:rows[pid] for pid in ids if pid in rows};write(case/'before.json',captured)
        leader=int((case/'leader.pid').read_text());assert leader in captured
        out,err=owner.communicate(b'c',timeout=15);out=prefix+out
        (case/'stdout').write_bytes(out);(case/'stderr').write_bytes(err)
        assert owner.returncode==0 and b'ALL SIBLINGS CLOSED' in out,(owner.returncode,err)
        after={pid:r for pid,r in table().items() if pid in captured or r['group']==leader};write(case/'at-terminal.json',after)
        assert not any(not r['state'].startswith('Z') for r in after.values()),after
        assert stamp()==inputs
        write(case/'summary.json',dict(passed=True,buildReceipt=digest(case/'build.json'),stdout=digest(case/'stdout'),stderr=digest(case/'stderr'),before=digest(case/'before.json'),after=digest(case/'at-terminal.json')))
        print('PASS Native protocol failure and sibling cleanup',case,flush=True)
    finally:
        if owner.poll() is None:owner.kill()
        rest,errors=owner.communicate(timeout=10)
        if not (case/'stdout').exists(): (case/'stdout').write_bytes(prefix+rest)
        if not (case/'stderr').exists(): (case/'stderr').write_bytes(errors)
        for pid,row in captured.items():
            if pid in table():
                try:os.kill(pid,9)
                except ProcessLookupError:pass


if __name__=='__main__':main()
