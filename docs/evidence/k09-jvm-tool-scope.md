# JVM tool verification scope

Date: 2026-10-22. JVM provider verification now hashes the complete JDK
installation and its existing worker/compiler/plugin/JUnit inputs without also
requiring the unused Kotlin/Native, LLVM, libffi, SDK and Xcode installations.

`InstalledToolchainVerifier.verifyJdk` returns one `VerifiedToolComponent`, not
`VerifiedToolchains`: it makes no SDK verification claim. It shares the strict
lock parser, supported Kotlin version and uname host checks with the full
verifier. All six lock digests remain structurally required. The full `verify`
path still requires and hashes all six roots, including deriving the SDK version
from verified SDKSettings.json bytes. Native provider behavior is unchanged.

Every call rehashes current file contents. There is no remembered verification,
mtime shortcut or weakened JVM compiler/worker manifest check. The stable,
caller-controlled tool-root requirement from [the inventory contract](k09-toolchains.md)
still applies through subsequent compiler execution.

## Validation

Both commands exited 0 in `/private/tmp/kvik-jvm-tool-scope`:

```sh
python3 tools/test_toolchains.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --konan-data /Users/yoda/.konan
python3 tools/test_compiler_tools.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --runtime /Users/yoda/dev/pet/kvik/.kvik/runtime/executables/d359ee862035119875cbab737e4f1e06fda7e631250d96d877edd88372a43751 \
  --artifacts /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts
```

The nine inventory scenario groups and seven provider scenario groups prove:

- JDK-only and full verification return exactly the same JDK inventory proof.
- Removing each unused installation leaves JVM verification successful while
  full verification rejects it.
- Same-size, preserved-mtime JDK and worker/compiler/plugin mutations fail;
  executable changes, additions, renames and malformed locks also fail.
- Full verification still rejects missing or mismatched SDK host data.
- The actual installed JDK, source-built worker and pinned compiler artifacts
  pass verification and perform one owned BTA compilation successfully.

The compiler-provider harness now includes `compiler/native/src` when compiling
its driver because the provider source set includes NativeTools.kt. This repairs
its source recipe; it does not add Native installations to JVM verification.

Retained logs are `/private/tmp/kvik-jvm-tool-scope-toolchains.log` and
`/private/tmp/kvik-jvm-tool-scope-compiler-tools.log`. Structured measurements are
`.kvik/tests/toolchains/installed-result.json`, `installed-jdk-result.json`, and
`.kvik/tests/compiler-tools/installed-result.json`; the last directory also
retains `worker-result.json` from the actual compilation.

| Observed operation | Raw installed file bytes hashed | Elapsed |
| --- | ---: | ---: |
| Full six-root verification | 6,130,936,949 | 24,131 ms |
| JDK-only verification | 474,365,165 | 319 ms |
| JVM provider verification plus owned BTA compile | JDK plus worker/compiler artifacts | 2,760 ms |

These are single same-host observations with uncontrolled filesystem cache
state, not a cold-cache benchmark or an end-to-end build speedup claim. The
inventory process peak RSS was 20,430,848 bytes; the provider driver reported
20,758,528 bytes, excluding the child JVM's memory. The filesystem refused an
invalid UTF-8 filename fixture, which remains an explicit test skip.

Independent source and retained-evidence review passed: the full/JDK proof
equality, mutation boundary and actual worker success were checked without
rerunning the compiler. No confirmed defect was found.
