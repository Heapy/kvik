# K09: production Native tool provider

Date: 2026-10-22. `InstalledNativeToolchainProvider(trustedToolLock, toolRoots,
konanDataRoot)` implements `NativeToolchainProvider` in Kotlin/Native. Inputs are the
caller-trusted installed-tool lock text, all six explicit component roots, and the explicit
KONAN_DATA_DIR root. Verification performs no network, subprocess, Java or path discovery.

Each successful call rehashes all six complete installed inventories using the existing
streaming verifier, checks host/SDK compatibility, and compares exactly four requested
identities: `native`, `jdk`, `sdk` use their component inventory digests, and
`native-dependencies` uses `nativeDependenciesIdentity(llvm, libffi, xcodeToolchain)`.
Digest lengths represent canonical inventory bytes, not total installation bytes.
The returned layout contains verified absolute compiler/JDK/SDK/data/developer paths.
The same four-key contract is used by compile and test actions.

## Supported installation contract

This adapter intentionally supports the reviewed Kotlin/Native 2.4.20 macOS-arm64 installation
layout, rather than implementing part of the compiler's Java-properties interpreter:

- `konan/konan.properties` must have SHA-256
  `f8a095afdc2b1fa052238c040562c254c36d7ce455a40a61cbf8065063ef4a4d`, length 36,666.
  This selects `llvm-21-aarch64-macos-essentials-97` and `libffi-3.3-1-macos-arm64`.
  The complete compiler distribution still must match the caller's trusted inventory.
- Verified LLVM/libffi roots must be actual direct directories at these exact names under
  `<konanDataRoot>/dependencies`. Symlink aliases in these directory paths are rejected.
- `.extracted` must name LLVM, libffi and `lldb-4-macos`; each directory must be nonempty,
  real, and lack `.unstable`. LLDB's presence is checked to prevent dependency extraction;
  its contents are **not** assigned a seventh tool identity. The supported typed compile/test
  actions do not launch LLDB. Debugger actions are outside this contract.
- `dependencies/cache` may be absent or contain only an empty regular `.lock`. Archives,
  linked directories, non-regular lock/marker files and oversized markers fail closed.
  Other extracted dependency versions may coexist; they are not selected by these properties.
- Neither the compiler's `konan/platforms` nor the data root's `platforms`, `klib`, `cache`,
  or `konan.properties` may exist. These are unsupported override/cache locations.
- The verified Xcode toolchain must be `Toolchains/XcodeDefault.xctoolchain` under the
  returned developer directory. That developer directory's default `MacOSX.sdk`, resolved
  physically, must equal the verified SDK root. The compiler/run_konan/java/ld launch files
  must be regular executable files with real directory ancestors.

The provider must be used with the typed Native executor's exact arguments/environment:
`airplaneMode=true`, explicit JAVA_HOME/SDKROOT/KONAN_DATA_DIR/DEVELOPER_DIR, isolated HOME,
and fixed LANG/PATH. This slice adds explicit `KONAN_USE_INTERNAL_SERVER=0` to both Native
compile and test action environments. Arbitrary environment or argument changes fail action
reconstruction before provider/process calls. The existing absent switch also disabled internal
server mode; the explicit value documents and tests that choice.

The caller must keep trusted roots **and data directory metadata** stable from verification
through process completion, including no concurrent dependency installation/extraction. This
provider does not acquire the compiler's installation lock across process execution, produce an
atomic snapshot, repair installations, or defend against an adversarial concurrent writer.
OS `/usr/bin` tools, Xcode developer metadata outside the six roots and the selected host remain
part of the existing local platform boundary. The provider does not establish sandboxing,
compiler hermeticity, portability or remote eligibility. No remote execution is enabled.

## Why extraction metadata matters

The installed compiler jar has SHA-256
`81aa0e438439fc7c1d591e05805af196dddc90965667165983c1a1ab3e6ba0fa`.
The investigation used `javap -p -c` on this actual pinned artifact (not production Java code):

```sh
/Users/yoda/.sdkman/candidates/java/25.0.4-librca/bin/javap -p -c \
  -classpath /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20/konan/lib/kotlin-native-compiler-embeddable.jar \
  org.jetbrains.kotlin.konan.util.DependencyProcessor \
  org.jetbrains.kotlin.konan.target.Distribution \
  org.jetbrains.kotlin.konan.properties.KonanPropertiesLoader \
  org.jetbrains.kotlin.konan.target.AppleConfigurablesImpl \
  org.jetbrains.kotlin.konan.util.InternalServer
```

`DependencyProcessor.downloadDependency` bytecode offsets 112–229 return early only for a
recorded, nonempty directory without `.unstable`; offsets 208–226 delete unstable trees.
Offsets 258–323 check airplane mode only when the archive is missing, and offsets 323–395
extract an existing archive regardless of airplane mode. Therefore offline arguments alone
cannot protect a verified tree from replacement. Fixture probes reject these states before
starting the compiler. This is bytecode inspection plus provider regression evidence; we did
not inject a hostile archive into the real installed dependencies.

`Distribution.additionalPropertyFiles` joins distribution and data-root `platforms/<name>`
files. `KonanPropertiesLoader.getDependencies` combines target dependencies with LLVM/libffi;
the reviewed properties also request LLDB. `AppleConfigurablesImpl` chooses local Xcode unless
internal-server mode is enabled. `InternalServer.isAvailable` returns false for unset/`0`, true
for `1`, and checks internal access for `auto`. These details motivate the restricted layout.

## Reproduction and observations

From this worktree:

```sh
python3 tools/test_native_tools.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-data /Users/yoda/.konan \
  --developer-directory /Applications/Xcode.app/Contents/Developer \
  --sdk /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk
```

The script builds a Native driver and process guardian, runs the focused Native action-policy
regression, tests fixture roots/identities/metadata, then runs one real library compilation
through `NativeCompilerExecutor` and the production provider. The real tool roots remain
read-only fixtures; action workspaces and processes are owned and removed/reaped. The driver
is deliberately launched with invalid inherited Xcode/data roots and internal-server `1`;
the child compiler receives the verified layout and fixed environment instead.

Fixture checks cover all requested identities and extra/missing keys; same-size content
changes with restored mtime in every component; missing extraction markers; unstable entries;
archive cache injection; FIFO metadata; symlinked LLDB; user override directories; equal-content
LLVM/SDK copies at incorrect selected paths; and re-pinned unsupported properties, non-executable
launchers, unstable trees and distribution platform overrides. Re-pinned cases distinguish
layout rejection from ordinary inventory mismatch. No full compiler suite is rerun.

Observed: the focused action-policy test passed (4 ms), all five provider scenario groups
passed, and real full verification plus owned library compilation took 40,477 ms. Peak Native
driver RSS was 21,348,352 bytes (about 20.4 MiB), excluding the compiler JVM. The later
expanded fixture-only rerun also passed all four fixture groups. This measures the combined
verification/compilation call, not independent hashing or compiler performance.

Measured real execution results are retained locally at
`.kvik/tests/native-tools/installed-result.json`. Full six-root verification is synchronous,
per-call and expensive; it has no cancellation or wall-time budget. Inventory hashing uses
64 KiB file buffers and streams canonical records; `.extracted` is bounded at 64 KiB and
metadata directory listings at 8,192 names. No whole SDK or LLVM file is loaded in memory.
