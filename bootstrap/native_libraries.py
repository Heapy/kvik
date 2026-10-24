"""Bounded, source-pinned Native interop recipes used by the composed runtime."""
from bootstrap.identity import inventory, canonical, digest
from bootstrap.pin_tools import native_target

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
    return ['-compiler-option', f'-I{root}/include', '-compiler-option', f'-I{root}/include/x86_64-linux-gnu',
            '-linker-option', f'-L{root}/lib/x86_64-linux-gnu']


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
        if name == 'toolchains' and native_target() == 'linux_x64':
            options += ['-linker-option', '-lcrypto']
        if name == "remote":
            clang, ar, flags = c_toolchain(roots)
            object_file = output / 'remote.o'
            run_owned([clang, '-std=c11', '-Wall', '-Wextra', '-Werror',
                *flags, '-c', source / 'remote.c', '-o', object_file], environment, log)
            run_owned([ar, 'rcs', output / 'libkvik-remote.a', object_file], environment, log)
            options += ['-libraryPath', str(output)]
        library = output / name
        run_owned([roots["kotlinNative"] / "bin/cinterop", "-def", source / (definition + ".def"), *options,
            "-target", native_target(), "-Xoverride-konan-properties", "airplaneMode=true", "-o", library], environment, log)
        libraries.append(library.with_suffix(".klib"))
    return libraries
