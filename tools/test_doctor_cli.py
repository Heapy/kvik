#!/usr/bin/env python3
"""Exercise read-only tool diagnostics through the composed native CLI."""
import argparse
import copy
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    provisioned = json.loads(args.tools.read_text())
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    validator = Draft202012Validator({**schema, '$ref': '#/$defs/doctor'})
    env = dict(PATH='/usr/bin:/bin', LANG='C', LC_ALL='C', TZ='UTC')
    with tempfile.TemporaryDirectory(prefix='kvik-doctor-') as raw:
        root = Path(raw).resolve()
        path = root / 'tools.json'

        def invoke(tools, targets=('jvm',), mode='build', code=0, binary=runtime):
            path.write_text(json.dumps(tools))
            before = inventory(root, True)
            command = [str(binary), 'doctor', '--tools', str(path), '--check', mode]
            for target in targets:
                command.extend(['--target', target])
            process = subprocess.run(command, cwd=root, env=env, stdin=subprocess.DEVNULL,
                                     capture_output=True, text=True, timeout=120)
            assert process.returncode == code, (process.returncode, process.stdout, process.stderr)
            result = json.loads(process.stdout)
            validator.validate(result)
            assert result['status'] == ('passed' if code == 0 else 'incomplete'), result
            assert result['targets'] == sorted(set(targets)), result
            assert result['check'] == mode and result['scope'] == 'tool-installations', result
            assert inventory(root, True) == before, 'doctor changed input files or launched a fixture executable'
            return {(c['id'], c['target']): c for c in result['checks']}

        # Real pinned JDK, compiler artifacts and runtime bundle. No project or dependency store needed.
        result = invoke(provisioned, mode='test')
        assert all(c['status'] == 'passed' for c in result.values())
        print('PASS provisioned JVM compilation and test tools', flush=True)

        # Caller-pinned tiny fixtures allow corruption and no-execution checks without altering real tools.
        fake = copy.deepcopy(provisioned)
        for component in fake['roots']:
            fake['roots'][component] = str(root / ('absent-' + component))
        fake['konanData'] = str(root / 'absent-konan-data')
        jdk = root / 'jdk'
        (jdk / 'bin').mkdir(parents=True)
        executable = jdk / 'bin/java'
        executable.write_text('#!/bin/sh\ntouch "' + str(root / 'unexpected-java-launch') + '"\n')
        executable.chmod(0o755)
        fake['roots']['jdk'] = str(jdk)
        fake['toolLock']['components']['jdk'] = digest(canonical(inventory(jdk, True)))
        invoke(fake, targets=('js', 'jvm', 'wasmJs', 'jvm'))
        invoke(fake, mode='test')
        print('PASS scoped JVM/JS/Wasm tools; unrelated Native roots absent; tools never executed', flush=True)

        result = invoke(fake, targets=('js', 'wasmJs'), mode='test', code=3)
        assert all(result['compilation-tools', t]['status'] == 'passed' for t in ('js', 'wasmJs'))
        assert all(result['test-runtime', t]['status'] == 'failed' for t in ('js', 'wasmJs'))
        node = root / 'node'
        (node / 'bin').mkdir(parents=True)
        node_executable = node / 'bin/node'
        node_executable.write_text('#!/bin/sh\ntouch "' + str(root / 'unexpected-node-launch') + '"\n')
        node_executable.chmod(0o755)
        fake['node'] = {'root': str(node), 'identity': digest(canonical(inventory(node, True)))}
        invoke(fake, targets=('js', 'wasmJs'), mode='test')
        node_executable.write_text('changed')
        result = invoke(fake, targets=('js',), mode='test', code=3)
        assert result['compilation-tools', 'js']['status'] == 'passed'
        assert result['test-runtime', 'js']['status'] == 'failed'
        node_executable.write_text('#!/bin/sh\ntouch \"' + str(root / 'unexpected-node-launch') + '\"\n')
        node_executable.chmod(0o641)
        assert fake['node']['identity'] == digest(canonical(inventory(node, True)))
        if not os.access(node_executable, os.X_OK):
            result = invoke(fake, targets=('js',), mode='test', code=3)
            assert result['test-runtime', 'js']['status'] == 'failed'
        else:
            print('SKIP Node access denial: current user can execute mode 0641', flush=True)
        print('PASS Node required only for tests; missing, changed and inaccessible installation rejected', flush=True)

        executable.write_text('changed')
        incompatible = 'linuxX64' if platform.system() == 'Darwin' else 'macosArm64'
        result = invoke(fake, targets=('jvm', incompatible), code=3)
        assert all(result['compilation-tools', t]['status'] == 'failed' for t in ('jvm', incompatible))
        assert result['tools-declaration', None]['status'] == 'passed'
        fake['toolLock']['components']['jdk'] = digest(canonical(inventory(jdk, True)))
        executable.chmod(0o644)
        fake['toolLock']['components']['jdk'] = digest(canonical(inventory(jdk, True)))
        result = invoke(fake, code=3)
        assert 'regular executable' in result['compilation-tools', 'jvm']['diagnostic']['message']
        executable.chmod(0o755)
        fake['toolLock']['components']['jdk'] = digest(canonical(inventory(jdk, True)))
        executable.chmod(0o641)
        assert fake['toolLock']['components']['jdk'] == digest(canonical(inventory(jdk, True)))
        if not os.access(executable, os.X_OK):
            result = invoke(fake, code=3)
            assert result['compilation-tools', 'jvm']['status'] == 'failed'
        else:
            print('SKIP Java access denial: current user can execute mode 0641', flush=True)
        executable.chmod(0o755)
        fake['toolLock']['components']['jdk'] = digest(canonical(inventory(jdk, True)))
        broken = copy.deepcopy(fake)
        broken['compilerArtifacts'] = str(root / 'missing-artifacts')
        result = invoke(broken, mode='test', code=3)
        assert result['compilation-tools', 'jvm']['status'] == 'failed'
        assert result['test-runtime', 'jvm']['status'] == 'passed'
        print('PASS corrupt JDK, nonexecutable Java, missing compiler artifacts and independent target failures', flush=True)

        for change in ('host', 'version', 'shape'):
            broken = copy.deepcopy(fake)
            if change == 'host':
                broken['toolLock']['host']['release'] = 'different'
            elif change == 'version':
                broken['schemaVersion'] = 2
            else:
                broken['unknown'] = True
            result = invoke(broken, mode='test', code=3)
            assert result['tools-declaration', None]['status'] == 'failed'
            assert result['compilation-tools', 'jvm']['status'] == 'skipped'
            assert result['test-runtime', 'jvm']['status'] == 'skipped'
        if platform.system() == 'Darwin':
            result = invoke(fake, targets=('iosArm64',), mode='test', code=3)
            assert result['test-runtime', 'iosArm64']['status'] == 'failed'
            assert 'unsupported' in result['test-runtime', 'iosArm64']['diagnostic']['message']
        print('PASS malformed declarations skip dependent checks; unsupported host tests remain failures', flush=True)

        if platform.system() == 'Darwin':
            native_tools = copy.deepcopy(provisioned)
            native_root = root / 'native-fixture'
            data = native_root / 'data'
            dependencies = data / 'dependencies'
            developer = native_root / 'Xcode/Contents/Developer'
            llvm = 'llvm-21-aarch64-macos-essentials-97'
            ffi = 'libffi-3.3-1-macos-arm64'
            roots = {'jdk': native_root / 'jdk', 'kotlinNative': native_root / 'native',
                     'llvm': dependencies / llvm, 'libffi': dependencies / ffi,
                     'sdk': developer / 'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk',
                     'xcodeToolchain': developer / 'Toolchains/XcodeDefault.xctoolchain'}
            for directory in roots.values():
                directory.mkdir(parents=True)
                (directory / 'tool').write_bytes(b'fixture')
            executables = [roots['jdk'] / 'bin/java', roots['kotlinNative'] / 'bin/konanc',
                           roots['kotlinNative'] / 'bin/run_konan', roots['xcodeToolchain'] / 'usr/bin/ld']
            for executable in executables:
                executable.parent.mkdir(parents=True, exist_ok=True)
                executable.write_text('#!/bin/sh\ntouch "' + str(root / 'unexpected-native-launch') + '"\n')
                executable.chmod(0o755)
            (roots['sdk'] / 'SDKSettings.json').write_text('{"Version":"fixture"}')
            (roots['kotlinNative'] / 'konan').mkdir()
            shutil.copyfile(Path(provisioned['roots']['kotlinNative']) / 'konan/konan.properties',
                            roots['kotlinNative'] / 'konan/konan.properties')
            (dependencies / 'lldb-4-macos').mkdir()
            (dependencies / 'lldb-4-macos/presence').touch()
            (dependencies / '.extracted').write_text(llvm + '\n' + ffi + '\nlldb-4-macos\n')
            native_tools['roots'] = {k: str(v) for k, v in roots.items()}
            native_tools['konanData'] = str(data)
            native_tools['toolLock']['host']['sdk'] = 'fixture'
            native_tools['toolLock']['components'] = {k: digest(canonical(inventory(v, True))) for k, v in roots.items()}
            invoke(native_tools, targets=('macosArm64',), mode='test')
            for executable in executables:
                executable.chmod(0o641)
                assert native_tools['toolLock']['components'] == {k: digest(canonical(inventory(v, True))) for k, v in roots.items()}
                if not os.access(executable, os.X_OK):
                    result = invoke(native_tools, targets=('macosArm64',), code=3)
                    assert result['compilation-tools', 'macosArm64']['status'] == 'failed'
                else:
                    print(f'SKIP Native access denial: current user can execute {executable} mode 0641', flush=True)
                executable.chmod(0o755)
            print('PASS Native installation layout and current-user access to compiler, launcher, Java and linker', flush=True)

        copied = root / 'runtime'
        shutil.copytree(runtime.parent, copied)
        (copied / 'guardian').chmod(0o644)
        result = invoke(fake, binary=copied / runtime.name, code=3)
        assert result['runtime-guardian', None]['status'] == 'failed'
        assert result['compilation-tools', 'jvm']['status'] == 'passed'
        assert 'executable' in result['runtime-guardian', None]['diagnostic']['message']
        (copied / 'guardian').chmod(0o641)
        if not os.access(copied / 'guardian', os.X_OK):
            result = invoke(fake, binary=copied / runtime.name, code=3)
            assert result['runtime-guardian', None]['status'] == 'failed'
        else:
            print('SKIP guardian access denial: current user can execute mode 0641', flush=True)
        print('PASS unchanged guardian bytes without current-user execution permission rejected', flush=True)

        for bad in ([], ['--tools'], ['--tools', str(path)], ['--target', 'jvm'],
                    ['--tools', str(path), '--target', 'android'],
                    ['--tools', str(path), '--target', 'jvm', '--check', 'unknown'],
                    ['--tools', str(path), '--tools', str(path), '--target', 'jvm']):
            process = subprocess.run([str(runtime), 'doctor', *bad], cwd=root, env=env,
                                     capture_output=True, text=True, timeout=20)
            assert process.returncode == 2, process.stdout
            assert json.loads(process.stdout)['diagnostic']['code'] == 'KVIK_DOCTOR_ARGUMENTS'
        print('PASS invalid CLI arguments', flush=True)


if __name__ == '__main__':
    main()
