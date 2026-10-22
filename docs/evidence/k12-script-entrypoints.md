# Script main discovery from compiled classes

The pure Kotlin helper `selectScriptMainClass` reads only script-owned class-file
bytes. It finds a declared public static `main(String[]):void`, or selects an
explicit `mainClass` among those candidates. Zero/multiple candidates are errors.
It checks class identity against the relative output path and never loads classes
or initializes the script. Dependency classes are excluded by the caller contract.
Runtime graph/CLI integration remains pending.

The parser follows [JVMS class-file framing and method metadata](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html).
It bounds class count, individual/aggregate bytes, constant-pool references and
attribute lengths, rejects truncation and trailing data, and supports modified
UTF-8 names and double-slot constants. Validated class names are memoized by UTF8
constant index, including distinct Class entries aliasing one name. It is not a
bytecode verifier; inherited mains, Java instance-main extensions, preview class
versions and versions beyond Java 25 are outside inference support.

## Executed gate

Frozen implementation/test source `72e6634` passed actual job `22090`, terminal
exit 0, in `/private/tmp/kvik-k12-script-entrypoints`:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_script_entrypoints.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --artifact-cache /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts
```

The harness compiled the real Kotlin fixtures separately for JVM targets 11 and
25 with pinned compiler artifacts, then embedded the exact resulting class bytes
in generated tests executed by Kotlin/Native. It exercised top-level no-argument
main's generated bridge, `@file:JvmName`, `@JvmStatic`, a non-static helper method,
ambiguity and explicit selection. The fixture has a file-writing initializer;
its sentinel remained absent. Compiler JVMs were invocation-owned; fixture
classes were never launched or loaded for discovery.

**Ten Native tests passed** in 18 ms: eight structural/selection regressions and
two real compiler-output tests. A 50,000-interface fixture aliases one 60,000-byte
name; the fixed parser completed that regression in 14 ms. This is a single-run
observation, not a benchmark. The malformed fixture exercises framing cost,
not JVM semantic validity of duplicate interface names.

Receipt:
`/private/tmp/kvik-k12-script-entrypoints/.kvik/tests/script-entrypoints/case-wo9aueyg/receipt.json`,
15,845 bytes, SHA-256
`c27f47ca6f9bda188d8017e7b6d03bd4b62534b9291f6eddee084342de1c146c`.
It records 25 source/harness/support inputs, 18 tool/artifact inputs, all four
commands and raw logs, eight class outputs, generated test bytes and the Native
binary. Binary SHA-256:
`f4257da2c3787971896219ad83ba937551b109f86438cdcdb66bd8be027b6315`,
2,154,576 bytes. Test log SHA-256:
`1019d65e6afd9bbadf90ad2d7f30f4b1433c7ac02c6e6efc27fc708310874161`.
The JVM compiler emitted JDK native-access and Unsafe deprecation warnings;
compilation completed successfully. No network acquisition or daemon was used.

## Independent review

Source review found repeated decoding of aliased interface names could amplify
small inputs into billions of character operations. Commit `72e6634` fixed that
finding with UTF8-index memoization and the regression above. A precompile review
also replaced a JVM-only collection extension with portable ordering.

A separate retained-evidence audit approved the final source and verified every
recorded input/tool/log/binary hash, generated tests against exact class bytes,
and all ten test results. An independent metadata parser confirmed class versions
55/69 and the three main forms, including the initializer-bearing fixture.

Report: `/private/tmp/kvik-script-entrypoints-audit-cjum2vfp/report.json`, SHA-256
`dcfbd8add2070c65acb065b425229733a0073f9742df7577327979d9b7d97449`.
The audit ran no compiler or process tests. Recorded compiler launch inputs are
not a complete installed-toolchain inventory; initializer absence establishes
the tested metadata-only discovery path, not arbitrary script execution safety.
