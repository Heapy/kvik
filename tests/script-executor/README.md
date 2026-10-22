# JVM script executor boundary

`JvmScriptExecutor(runId, runDirectory, workingDirectory, host, content, toolchains)` consumes the typed `jvmScriptAction` contract. It reconstructs that contract into independent collections, verifies the requested tool identities through `JvmToolchainProvider`, and materializes all declared CAS inputs in a fresh private directory. `workingDirectory` is the caller's project or standalone companion root; script effects there are intentional. This is process ownership, not an application sandbox.

Main discovery examines only the script's own materialized `.class` files, with SHA-256/size checks and the existing 20,000-class, 256 MiB aggregate, 64 MiB per-class bounds. Dependency mains are excluded. Declared classpath order is preserved, followed by the provider's verified Kotlin standard library. The order permits explicitly declared classes/libraries to take precedence; the suite proves argv order, not compatibility of arbitrary replacement stdlib versions. `--disable-@files` preserves literal script arguments. The fixed base environment plus declaration is passed explicitly; ambient JVM flags are not inherited. Java receives private temporary/home system properties, while its process cwd remains the supplied root.

`observations` returns `JvmScriptObservation` values with the action result, `SETUP`/`MAIN_DISCOVERY`/`EXECUTION` phase, nullable exact host `ProcessResult`, cancellation latch, selected main, private directory, exact argv, tool-bound stdlib path, and diagnostics. Setup and discovery failures have no child exit. Main-selection failures are configuration diagnostics. EXECUTION means an owned launch was attempted; it does not certify Java entered main. In particular the current host's unannotated 125 cannot distinguish guardian setup failure from a user's ordinary exit 125. The separately planned additive host setup-failure result will remove that ambiguity.

A terminal process observation is published only after `poll` and `close` prove complete cleanup. Protocol/cleanup exceptions propagate rather than becoming ordinary child exits. Early close drains ownership without inventing a result that the host did not return. Requested cancellation remains cancellation even if the host already observed exit zero, and the original host result is retained. Actions have no cached outputs and always use NEVER. Each invocation retains separate `script.stdout` and `script.stderr` files.

The executor and stores are thread confined. Callers must keep verified tool installations, cwd, CAS and private run roots stable during use. Materialization uses the supplied store's supported blob bound; it does not add an independent 64 MiB artifact limit. Main-discovery buffers alone have the bounds above. ByteArray materialization still allocates one artifact at a time, and main discovery retains its bounded aggregate map. The production provider's full inventory verification remains a separate integration boundary.

Run the focused suite after acquiring the shared compiler/process slot:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_script_executor.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --artifact-cache /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts
```

The harness compiles one real Kotlin/JVM target-11 fixture, the actual Native ownership guardian/bridge and one Native test executable. It records source/support/tool identities, complete commands/environments/logs, class outputs, binary hashes, exact requests/observations, stream/effect files and immediate post-terminal child checks. There are 13 fake-host/real-CAS groups and six real-Java groups (ten invocations). The fixture provider freshly hashes the consumed Java launcher and stdlib for every call; it does not claim to replace complete installed-JDK or worker-bundle verification. Those are tested by the production provider gate. SDK, JDK support and Native dependency trees must remain stable during compilation; the receipt does not claim their complete inventory.

Status: source and harness prepared; Native compilation and actual process evidence are pending the coordinated slot.
