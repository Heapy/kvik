# Bounded wildcard-all POM exclusions

The actual `org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20` POM declares
runtime `kotlin-reflect:1.6.10` and `kotlinx-coroutines-core-jvm:1.8.0` edges
with exactly one exclusion whose group and artifact are both literal `*`.
K13's explicit compiler API dependency requires this published runtime closure.

The resolver retains the dependency artifact and its selected variant,
capabilities, artifact digest and metadata, but does not expand dependencies
along that edge. Separate expansion states preserve ordinary paths to the same
component regardless encounter order. Capability identity still belongs to
the component, not the expansion state. Redirects carry the expansion policy.
No partial exclusion, property-based exclusion or dependency-management
exclusion merging is implemented. Those combinations remain explicit errors.

This follows Maven's [per-dependency exclusion semantics](https://maven.apache.org/guides/introduction/introduction-to-optional-and-excludes-dependencies.html)
and its [wildcard matcher and derived child selector](https://raw.githubusercontent.com/apache/maven-resolver/master/maven-resolver-util/src/main/java/org/eclipse/aether/util/graph/selector/ExclusionDependencySelector.java).
It does not introduce Maven's general dependency mediation algorithm.

Pinned metadata fetched on 2026-10-22 is retained under
`tests/resolution/fixtures`, with exact URLs/SHA-256/byte counts in `manifest.json`.
`compiler-embeddable-artifacts.json` records the actual ordered 2-artifact API
and 8-artifact runtime closures. The compiler and BTA API bytes also match the
independently existing `experiments/k01/artifacts.lock.json`. Resolver tests
verify actual selections and locked metadata/artifact bytes, reject corrupt
metadata, and cover excluded-only plus ordinary/excluded paths in both orders,
partial/managed exclusions and invalid excluded-target metadata/artifacts.

Validation:

```sh
python3 tools/test_resolution.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20
```

The existing Native resolver suite plus the focused additions passes 24 tests.
The independent workspace-plugin graph harness also resolves and replays the
compiler closure through the v2 dependency-lock/CAS integration.
