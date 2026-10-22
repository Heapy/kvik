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
`k08-kotest-adapters.md`. Public CLI graph/composition execution remains a separate
integration gate.
