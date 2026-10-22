# K05 default variant capabilities

The unchanged kotaml public CLI resolve arguments exposed explicit capabilities in
`org.jetbrains.kotlin:kotlin-test-junit5:2.4.20`. The resolver now selects ordinary
coordinate dependencies only from variants that provide that coordinate's default
capability. Explicit feature variants are not interchangeable merely because their
platform and usage attributes match. An absent or empty capability list supplies
the implicit default; a nonempty list replaces it. These rules follow the
[Gradle capability API](https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/ModuleDependencyCapabilitiesHandler.html),
[capability documentation](https://docs.gradle.org/current/userguide/component_capabilities.html),
and [Module Metadata specification](https://raw.githubusercontent.com/gradle/gradle/master/platforms/documentation/docs/src/docs/design/gradle-module-metadata-latest-specification.md).

Literal provided capabilities are parsed strictly. Independent providers claiming
the same capability fail; available-at aliases share their terminal provider.
Version pins do not imply capability substitution. Requested-capability selectors,
ambiguous variants, malformed declarations, and capability conflict resolution
remain unsupported and fail explicitly.

Producer metadata is retained unchanged under `tests/resolution/fixtures`, with
URL, SHA-256, and byte size in `manifest.json`. The original failing junit5 metadata
has SHA-256 `7b0b9a68debb4750ad920a430c7be087f69705d47125f5056e8787ffdc2a76ad`.
Tests verify its 8-artifact API and 10-artifact runtime closures against the pinned
kotaml JVM artifact reference, including supplied JUnit 5.13.4 pins. Ordinary
`kotlin-test` resolution does not fetch JUnit4/TestNG feature alternatives.

Validation on macosArm64:

```sh
python3 tools/test_resolution.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
```

All 16 Native tests passed. All 12 selected production fixture artifacts still
match the KTC cache bytes. Regressions cover default/feature filtering, explicit
JUnit5 selection, redirect aliases, provider conflicts, unsupported selectors,
and malformed/ambiguous capabilities.

The separate full dependency probe uses the exact retained public CLI arguments:

```sh
python3 tools/test_kotaml_resolution.py \
  --resolve-command /Users/yoda/dev/pet/kvik/.kvik/tests/kotaml-cli/case-r6zd4237/resolve.command.json \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

It passed capability selection and reached a separate unsupported parent POM:
[`io.github.java-diff-utils:java-diff-utils:4.16`](https://repo.maven.apache.org/maven2/io/github/java-diff-utils/java-diff-utils/4.16/java-diff-utils-4.16.pom),
SHA-256 `434d598a1e1813111385235161627a4c4ebd62134f7054428d3e202b32cf39de`,
2,304 bytes. Its version is inherited from
`io.github.java-diff-utils:java-diff-utils-parent:4.16`. The probe failed explicitly
with `Unsupported POM semantics (parents, profiles, repositories or relocation)`.
The POM is retained as a rejection regression. Raw fetched metadata, URL/digest
provenance, effective configuration, and failure output are retained at
`.kvik/tests/kotaml-resolution/case-ot4qsgfv` in the capability worktree.

This is not a passing full kotaml resolution or test gate. No dependency pins,
scope expectations, fixture sources, or assertions were widened. The probe will
compare all eight exact domain artifact sets and perform CAS-only replay once
the separately scoped POM support allows resolution to complete.

Independent review approved `689b6f6` before integration as `d36858b`. Native
probes additionally rejected explicit-versus-POM implicit provider collisions
in both root orders, accepted multi-hop aliases sharing one terminal provider,
and resolved/replayed independent providers in separate consumer domains. The
actual JUnit5 closures and parent-POM rejection were checked independently in
`/private/tmp/kvik-caps-fixture-u94f1jj6`; the additional probe is retained under
`/private/tmp/kvik-capabilities-review`.

The subsequent [parent-POM slice](k05-parent-poms.md) completes the fixture's
resolution, followed by the separately validated [full public CLI run](k09-kotaml-cli.md).
