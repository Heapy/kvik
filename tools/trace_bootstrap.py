#!/usr/bin/env python3
"""Run an unchanged bootstrap with receipts at its owned compiler-call boundary.

Invoke with pinned Python -I -S -B, outside the selected source checkout. This
instrumentation does not replace process ownership or change compiler arguments.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def digest(path):
    hasher = hashlib.sha256()
    size = 0
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(block)
            size += len(block)
    return dict(hash=hasher.hexdigest(), sizeBytes=size)


def append(path, value):
    data = (json.dumps(value, ensure_ascii=False, sort_keys=True) + '\n').encode()
    if len(data) > 4 * 1024 * 1024:
        raise ValueError('Oversized bootstrap trace entry')
    # Close the receipt before delegation: it is never inherited by a compiler.
    with Path(path).open('ab') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def command_inputs(command, output):
    """Retain consumed file digests and copies of generated/staged source inputs."""
    paths = set()
    arguments = list(map(str, command))
    for index, argument in enumerate(arguments):
        if index and arguments[index - 1] in ('-cp', '-classpath'):
            paths.update(Path(part) for part in argument.split(os.pathsep) if part)
        elif argument.startswith('/'):
            paths.add(Path(argument))
    executable = Path(arguments[0])
    staged = None
    if executable.name in ('konanc', 'cinterop'):
        home = executable.parent.parent
        paths.update([home/'bin/run_konan', home/'tools/env_blacklist'])
        paths.update((home/'konan/lib').glob('*.jar'))
    if executable.name == 'konanc' and arguments[-1].endswith('/sources/Registry.kt'):
        staged = Path(arguments[-1]).parent
        paths.update(path for path in staged.rglob('*') if path.is_file())
    result = {}
    for path in sorted(paths):
        if not path.is_file():
            continue
        value = digest(path)
        item = dict(digest=value, executable=bool(path.stat().st_mode & 0o111))
        if path.suffix in ('.kt', '.c', '.h', '.def') or (staged and path.is_relative_to(staged)):
            target = output/'inputs'/value['hash']
            target.parent.mkdir(exist_ok=True)
            data = path.read_bytes()
            if hashlib.sha256(data).hexdigest() != value['hash']:
                raise ValueError('Compiler input changed during trace capture')
            if target.exists():
                assert target.read_bytes() == data
            else:
                with target.open('xb') as stream:
                    stream.write(data)
            item['retained'] = str(target.relative_to(output))
        result[str(path)] = item
    return result


def traced_delegate(original, output):
    sequence = 0

    def run(command, environment, log, timeout=300):
        nonlocal sequence
        sequence += 1
        number = sequence
        append(output/'commands.jsonl', dict(kind='start', sequence=number,
            command=list(map(str, command)), environment=dict(environment),
            environmentBoundary='requested at run_owned; guardian adds reserved transport metadata internally',
            timeoutSeconds=timeout, log=str(log.name), inputs=command_inputs(command, output)))
        started = time.monotonic()
        try:
            result = original(command, environment, log, timeout)
        except BaseException as error:
            try:
                append(output/'commands.jsonl', dict(kind='exception', sequence=number,
                    seconds=time.monotonic()-started, type=type(error).__name__, message=str(error)))
            except BaseException:
                # An incomplete receipt fails verification; preserve the compiler's
                # original failure even if retaining that failure also fails.
                raise error
            raise
        append(output/'commands.jsonl', dict(kind='returned', sequence=number,
            seconds=time.monotonic()-started))
        return result

    return run


def git(root, *arguments):
    return subprocess.check_output(['/usr/bin/git', '-c', 'core.fsmonitor=false',
        '--no-optional-locks', *arguments], cwd=root, timeout=30)


def source_inventory(root):
    paths = git(root, 'ls-files', '-z').decode().split('\0')
    return {name: digest(root/name) for name in paths if name and (root/name).is_file()}


def verify_import_sources(root, inventory):
    # -B prevents writes, but Python can still execute an existing .pyc. This
    # gate needs source execution from the exact checkout, including on retries.
    candidates = [*root.glob('*.py'), *root.glob('*.pyc'), *root.glob('*.pyo'),
        root/'__pycache__', *root.glob('*/__init__.py'), *(root/'bootstrap').rglob('*')]
    for path in candidates:
        if not path.exists():
            continue
        assert path.name != '__pycache__' and path.suffix not in ('.pyc', '.pyo'), 'Preexisting Python bytecode cache'
        if path.is_file() and path.suffix == '.py':
            name = path.relative_to(root).as_posix()
            assert not path.is_symlink() and name in inventory and digest(path) == inventory[name], name


def validate_arguments(arguments):
    paths = {'--java-home', '--konan-home', '--konan-data', '--cache-dir', '--artifact-cache'}
    switches = {'--print-launch', '--offline'}
    seen = {}
    index = 0
    while index < len(arguments):
        flag = arguments[index]
        assert flag in paths | switches and flag not in seen, 'Unsupported or duplicate bootstrap flag: '+flag
        index += 1
        value = None
        if flag in paths:
            assert index < len(arguments) and Path(arguments[index]).is_absolute(), 'Expected absolute path for '+flag
            value = arguments[index]
            index += 1
        seen[flag] = value
    assert {'--java-home', '--konan-home', '--cache-dir', '--artifact-cache', *switches} <= seen.keys()
    cache = Path(seen['--cache-dir']).resolve()
    assert not cache.exists(), 'A fresh cache is required to trace actual compilation'
    return cache


def preserving_failure(operation, finalizer):
    primary = None
    try:
        return operation()
    except BaseException as error:
        primary = error
        raise
    finally:
        try:
            finalizer()
        except BaseException:
            if primary is not None:
                raise primary
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--expected-commit', required=True)
    parser.add_argument('--receipt-dir', type=Path, required=True)
    parser.add_argument('bootstrap_arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    assert sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode, 'Use Python -I -S -B'
    # macOS may add its text-encoding setting even to an env -i launch.
    assert set(os.environ) <= {'PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'TZ', '__CF_USER_TEXT_ENCODING'}, 'Use an explicit env -i environment'
    root = args.source_root.resolve(strict=True)
    wrapper = Path(__file__).resolve(strict=True)
    assert not wrapper.is_relative_to(root), 'Instrumentation must be outside the frozen source checkout'
    output = args.receipt_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    head = git(root, 'rev-parse', 'HEAD').decode().strip()
    assert head == args.expected_commit
    git(root, 'diff', '--exit-code', 'HEAD', '--')
    before = source_inventory(root)
    verify_import_sources(root, before)
    arguments = args.bootstrap_arguments
    if arguments[:1] == ['--']:
        arguments = arguments[1:]
    validate_arguments(arguments)
    invocation = dict(sourceRoot=str(root), sourceCommit=head, sources=before,
        wrapper=dict(path=str(wrapper), digest=digest(wrapper)),
        python=dict(path=str(Path(sys.executable).resolve()), digest=digest(sys.executable)),
        argv=list(sys.argv), cwd=str(Path.cwd()), parentEnvironment=dict(os.environ),
        bootstrapArguments=arguments, sourceImportsWithoutPreexistingBytecode=True)
    append(output/'invocation.jsonl', invocation)
    sys.path.insert(0, str(root))
    from bootstrap import launcher
    assert Path(launcher.__file__).resolve() == root/'bootstrap/launcher.py'
    assert launcher.ROOT.resolve() == root
    original = launcher.run_owned
    launcher.run_owned = traced_delegate(original, output)
    saved_argv = sys.argv
    sys.argv = [str(root/'kvik'), *arguments]
    def finish():
        launcher.run_owned = original
        sys.argv = saved_argv
        modules = {}
        for name, module in sys.modules.items():
            if name == 'bootstrap' or name.startswith('bootstrap.'):
                path = Path(module.__file__).resolve()
                assert path.is_relative_to(root/'bootstrap'), name
                modules[name] = dict(path=str(path), digest=digest(path))
        after = source_inventory(root)
        verify_import_sources(root, after)
        python_unchanged = invocation['python']['digest'] == digest(sys.executable)
        append(output/'finished.jsonl', dict(sourcesUnchanged=before == after,
            wrapperUnchanged=invocation['wrapper']['digest'] == digest(wrapper),
            pythonUnchanged=python_unchanged, modules=modules))
        assert before == after and invocation['wrapper']['digest'] == digest(wrapper) and python_unchanged
    preserving_failure(launcher.main, finish)


if __name__ == '__main__':
    main()
