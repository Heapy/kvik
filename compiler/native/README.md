# Native workspace compiler plugins

`NativeCompilation.pluginApplications` reuses `JvmPluginApplication`. Empty applications are omitted from the serialized action, preserving existing action identities. Source-free links reject plugin applications. Plugin reports are separate declared output roots under `plugins/<id>/`; graph consumers must propagate KLIBs separately from those reports.

Workspace applications retain their complete ordered JVM producer/runtime classpath, literal options, declared input files and output directories. Their actions add `jvm-compiler`, `jvm-worker` and `junit` to the four Native identities. `NativeCompilerExecutor` accepts an optional `JvmToolchainProvider`, verifies its exact identity subset and shared JDK, and uses the explicit `NativeToolchainLayout.nativeHome`. The default home derivation only preserves existing constructor calls; production providers should supply the verified root.

The owned `NativePluginWorker` loads the verified Native compiler in an isolated parent loader. Shared validation authorizes an explicitly declared compiler API JAR only against the pinned JVM compiler manifest and checks that Native supplies the registrar, processor and IR API classes. It retains every declared classpath entry. Ordinary compiler arguments are parsed first; plugin classpath and option arrays are injected afterwards, avoiding Native CLI comma splitting. Constructors run during validation and compiler loading, as with the JVM adapter. Plugins remain trusted executable build code, not a sandbox.

The worker emits a structured `WorkerResult`; Native execution checks request identity, exit consistency and status before output capture. Missing/malformed results, compilation failures, timeout and cancellation publish no successful output tree. Existing no-plugin invocations and test execution retain their prior paths.

Focused checks:

```
python3 -B tools/test_native_workspace_adapter.py \
  --fixture /private/tmp/kvik-k13-native-plugin-probe/.kvik/tests/native-workspace-plugins/case-wxr_yudr \
  --artifacts /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20
```

Add `--units-only` for the existing nine Native tests plus three new action/executor groups. The retained successful worker case is `case-uwu40c75`: exact comma/equals options, ordered classpath and Native parent origins, missing-runtime/API rejection, structured compiler diagnostics, and the shared JVM validation regression all pass. Native case `case-8nf3yh47` passed all 12 tests. An initial worker compilation in `case-k9o1t6gm` failed for a missing experimental API opt-in; corrected before the passing run. These checks reuse the compatibility fixture privately and do not claim the combined public CLI gate, which follows separately.
