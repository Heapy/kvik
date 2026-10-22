# K08 Native Kotest graph integration

Selected `nativeTestSettings` introduces a `module:macosArm64:ksp-test`
generation node. Its inputs include test sources, their common-source
membership, the module's freshly compiled main klib, visible module dependency
klibs, Native test dependency artifacts, and the separately locked ordered KSP
runtime and processor classpaths. These host jars remain outside the Native
compiler's library/plugin paths.

The Native test link depends on the successful generator's exact raw launcher
output and uses the typed Kotest compilation mode. Its terminal test uses the
single-invocation Kotest executor with `NEVER` cache policy. A build-only request
does not generate or execute tests. The existing Native main-source recompilation
and visibility restrictions remain in force; this does not add hidden Native
link-library support or arbitrary processor versions.

```sh
python3 tools/test_project_graph.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

All 15 Native graph test groups passed (155 ms test execution). The new test
proves host-classpath separation, own-main input, generator-before-link edges,
raw-output content invalidation, generated source adaptation arguments,
single-process test action shape, and absence of generation for build-only
requests. Other groups retain JVM scopes/plugins/release behavior and existing
Native visibility/resource behavior. This pure graph check uses modeled verified
locks and outputs; real adapter execution is documented separately in
`k08-kotest-adapters.md`. Full public CLI execution remains a separate integration gate.


## Composed CLI integration

The public build dispatcher now routes the typed KSP generator and Native Kotest
runner through the same invocation-owned process host, verified Native/JDK tool
provider, scheduler and local cache as the other adapters. Generator observations
retain result, diagnostics and stdout/stderr paths. Kotest observations feed the
existing Native test-result stream and verification aggregation. The composition
includes `compiler/ksp/src`. Independent source review found no wiring defects.

The combined executable built successfully from composition
`0dcc6a76fd24a957a064a4bfb823205e17f7b188a592353141c1f9850195d678`:
SHA-256 `0240dffe4f5ea8f72ab914098d0383baff21a888319b14a87a3a4575f1a076e9`,
4,697,128 bytes. The compile phase took 85,847 ms on the existing named host;
this single observation is not a performance benchmark. Build logs are retained
at `/private/tmp/kvik-k08-composed-build.stdout` and `.stderr`, with the compiler
log in `.kvik/runtime/logs/0dcc6a76fd24a957a064a4bfb823205e17f7b188a592353141c1f9850195d678.log`.
All 11 contract schema examples and canonical/path checks passed after integration.
The three K16 commands also passed an offline smoke check through this executable,
with schema-valid responses and unchanged project/cache inventories, retained in
`.kvik/tests/package-queries-cli/composed-sfcsz3oo/`.

This proves composition and adjacent command integration, not the full upstream
Native consumer gate. The faithful kotaml configuration requires distinct API
and private link closures, currently rejected by `nativeVisibility`; that graph
and adapter extension remains necessary before claiming the full CLI run.
