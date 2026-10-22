"""Bounded, source-pinned Native interop recipes used by the composed runtime."""
from bootstrap.identity import inventory, canonical, digest

RECIPES = {
    "resolution": ("resolution/native", "resolution", {"resolution.h", "resolution.def"}),
    "toolchains": ("integration/toolchains/platform", "stream_hash", {"stream_hash.h", "stream_hash.def"}),
    "remote": ("cache/remote/platform", "remote", {"remote.c", "remote.h", "remote.def"}),
}


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
        options = ["-compiler-option", f"-I{source}"]
        if name == "resolution":
            options += ["-compiler-option", f"-I{roots['sdk']}/usr/include/libxml2"]
        if name == "remote":
            toolchain = roots['xcodeToolchain'] / 'usr/bin'
            object_file = output / 'remote.o'
            run_owned([toolchain / 'clang', '-std=c11', '-Wall', '-Wextra', '-Werror',
                '-isysroot', roots['sdk'], '-c', source / 'remote.c', '-o', object_file], environment, log)
            run_owned([toolchain / 'ar', 'rcs', output / 'libkvik-remote.a', object_file], environment, log)
            options += ['-libraryPath', str(output)]
        library = output / name
        run_owned([roots["kotlinNative"] / "bin/cinterop", "-def", source / (definition + ".def"), *options,
            "-target", "macos_arm64", "-Xoverride-konan-properties", "airplaneMode=true", "-o", library], environment, log)
        libraries.append(library.with_suffix(".klib"))
    return libraries
