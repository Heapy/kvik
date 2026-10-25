"""Bounded, source-pinned Native interop recipes used by the composed runtime."""
from bootstrap.identity import inventory, canonical, digest
from bootstrap.pin_tools import native_target, NATIVE_DEPENDENCIES
from pathlib import Path

RECIPES = {
    "files": ("cache/platform", "files", {"files.h", "files.def"}),
    "resolution": ("resolution/native", "resolution", {"resolution.h", "resolution.def"}),
    "toolchains": ("integration/toolchains/platform", "stream_hash", {"stream_hash.h", "stream_hash.def"}),
    "remote": ("cache/remote/platform", "remote", {"remote.c", "remote.h", "remote.def"}),
}


def c_toolchain(roots):
    if native_target() == "macos_arm64":
        directory = roots['xcodeToolchain'] / 'usr/bin'
        return directory / 'clang', directory / 'ar', ['-D_DARWIN_C_SOURCE', '-isysroot', roots['sdk']]
    directory = roots['system'] / 'bin'
    return directory / 'clang', directory / 'ar', ['-D_GNU_SOURCE', '-pthread']


def interop_options(roots):
    if native_target() == "macos_arm64":
        return []
    # System headers/libraries are explicitly inventoried by the Linux system component.
    root = roots['system']
    return ['-compiler-option', f'-I{root}/include', '-compiler-option', f'-I{root}/include/x86_64-linux-gnu']


def linker_options(roots):
    # cinterop ignores linker-option. Apply the verified host library search path
    # when linking the runtime; library names remain in their source-pinned .def files.
    if native_target() == 'macos_arm64':
        return []
    return ['-linker-option', f'-L{roots["system"]}/lib/x86_64-linux-gnu',
            '-linker-option', f'-L{roots["nativeToolchain"]}/x86_64-unknown-linux-gnu/lib64']


def compiler_properties():
    # The distribution also lists LLDB, which is a debugger rather than an input
    # to these compile/link recipes. Restrict eager dependency preparation to the
    # trees actually pinned by bootstrap; do not require an ambient LLDB cache.
    target = native_target()
    return 'airplaneMode=true;dependencies.' + target + '=' + ' '.join(NATIVE_DEPENDENCIES[target].values())


def runtime_properties(roots):
    """Only the host runtime links captured system C libraries, not consumer programs."""
    if native_target() == 'macos_arm64':
        return compiler_properties()
    # Ubuntu's captured libc linker script names /lib and /lib64. Require those
    # aliases to remain inside the inventoried /usr tree; do not mix its headers
    # and shared libraries with Konan's older glibc sysroot.
    if (roots['system'].resolve(strict=True) != Path('/usr') or
            Path('/lib').resolve(strict=True) != Path('/usr/lib') or
            Path('/lib64').resolve(strict=True) != Path('/usr/lib64')):
        raise ValueError('Linux runtime linking requires captured /usr and /lib, /lib64 aliases into it')
    gcc = roots['nativeToolchain'] / 'lib/gcc/x86_64-unknown-linux-gnu/8.3.0'
    if any(char in str(gcc) for char in ';=\r\n'):
        raise ValueError('Native toolchain path cannot contain property separators')
    return ';'.join([compiler_properties(), 'targetSysRoot.linux_x64=/',
        f'libGcc.linux_x64={gcc}', 'abiSpecificLibraries.linux_x64=usr/lib/x86_64-linux-gnu',
        'crtFilesLocation.linux_x64=usr/lib/x86_64-linux-gnu',
        'dynamicLinker.linux_x64=/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2'])


def select(root, names, relative):
    if not isinstance(names, list) or not all(isinstance(name, str) and name in RECIPES for name in names) or len(set(names)) != len(names):
        raise ValueError("Unsupported or duplicate Native library recipe")
    pins, files = [], []
    for name in names:
        directory, _, expected = RECIPES[name]
        path = relative(root, directory)
        tree = inventory(path)
        if {entry["path"] for entry in tree["files"]} != expected:
            raise ValueError(f"Unexpected Native library sources: {name}")
        pins.append({"id": "native-" + name, "kind": "local", "origin": directory, "revision": None, "tree": digest(canonical(tree))})
        files.extend((path / entry["path"], directory + "/" + entry["path"], entry["digest"]) for entry in tree["files"])
    return pins, files


def build(names, source_root, output, roots, environment, log, run_owned):
    libraries = []
    for name in names:
        directory, definition, _ = RECIPES[name]
        source = source_root / directory
        options = ["-compiler-option", f"-I{source}", *interop_options(roots)]
        if name == "resolution":
            include = roots['sdk'] / 'usr/include/libxml2' if native_target() == 'macos_arm64' else roots['system'] / 'include/libxml2'
            options += ["-compiler-option", f"-I{include}"]
        if name == "remote":
            clang, ar, flags = c_toolchain(roots)
            object_file = output / 'remote.o'
            run_owned([clang, '-std=c11', '-Wall', '-Wextra', '-Werror',
                *flags, '-c', source / 'remote.c', '-o', object_file], environment, log)
            run_owned([ar, 'rcs', output / 'libkvik-remote.a', object_file], environment, log)
            options += ['-libraryPath', str(output)]
        library = output / name
        run_owned([roots["kotlinNative"] / "bin/cinterop", "-def", source / (definition + ".def"), *options,
            "-target", native_target(), "-Xoverride-konan-properties", compiler_properties(), "-o", library], environment, log)
        libraries.append(library.with_suffix(".klib"))
    return libraries
