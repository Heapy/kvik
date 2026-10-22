# Native bootstrap context validation

`BootstrapLaunch` consumes the Python launcher's bounded invocation context in
Kotlin/Native. It checks exact versioned JSON fields, UTF-8, the 32 KiB limit,
the producer's ordered flag/value grammar, required data/cache paths, absolute
NUL-free paths, launcher ownership and the runtime's cache/composition path.
Paths and shell-looking text remain literal argv entries. Lexical `..` and
symlink spelling in tool paths are preserved because normalization can change
which installation a path selects.

The parsed context can produce only the two bootstrap query commands. A current
context must match the running executable's compiled composition and actual
runtime path. A replacement must match the newly observed composition and
preserve the original source root, launcher, interpreter and all bootstrap
arguments, including offline/no-build policy. Composition query replies have a
separate exact schema and a 1 KiB bound.

These are consistency checks on trusted invocation context. They neither
authenticate external input nor replace bootstrap's executable verification.
The foreground loop does not yet call this contract: owned query scheduling,
draining work, rebuild failure recovery and native process replacement remain
the next integration step. This is not a passed source-recomposition gate.

Reproduction:

```sh
python3 tools/test_foreground_bootstrap.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20
```

Four Kotlin/Native tests passed. The harness obtains descriptor bytes from the
actual Python producer and compiles them into the Native tests. Checks cover
literal argv, current/replacement bindings, changed policy/source ownership,
unknown/repeated/misordered flags, missing required paths, wrong runtime paths,
invalid UTF-8, extra fields, duplicate fields and oversized responses. This
cross-language contract test does not launch the supplied fixture paths.
