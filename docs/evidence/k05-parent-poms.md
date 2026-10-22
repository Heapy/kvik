# K05 published parent POM subset

The exact kotaml CLI resolution arguments now resolve all eight dependency
domains with the same coordinate/digest sets as the unchanged KTC reference in
`compiler/jvm/kotaml-artifacts.lock.json`. Main compile/runtime contain 5/6 artifacts,
test compile/runtime 25/37, JSON main 4/7, and JSON test 24/38. Serialization compiler
plugin dependencies remain in their separate domains. No source, assertion,
dependency pin, or expected classpath was changed.

The Native probe produced 54 locked selections, 41 distinct artifacts including
the separate compiler plugin, and 64 metadata identities. It also loaded the
persisted lock using CAS-only replay. This establishes dependency resolution and
replay; it does not establish public CLI compilation or 2,038-test execution.

## Supported model

`resolution/src/kvik/resolution/PomModel.kt` constructs the dependency-relevant
part of a published POM. libxml2 still parses XML under the existing DTD/network,
byte, node, and depth restrictions. Parent POMs are fetched by exact coordinates
from configured repositories, never from local `relativePath`. Only absent,
empty, or conventional `../pom.xml` relative paths are accepted. Parent coordinates
are not dependency-version substitutions. Parent identities and POM packaging
are checked, ancestry is bounded to 16 models, and parent retrieval contributes
to the existing request, component, and aggregate byte limits. Every consulted
parent's URL/digest is retained in the lock closure.

Missing group/version can come from the declared parent. Packaging and artifactId
are not inherited. A child dependency or management declaration replaces an
inherited declaration with the same group/artifact/type/classifier key. Child
declarations appear first. Property inheritance is assembled before expansion;
child property values can therefore affect inherited version expressions.
Supported interpolation uses declared properties and `project`/`pom`
groupId/artifactId/version, with cycle, depth, and expansion bounds. Ambiguous
identities produced by interpolation fail explicitly.

After inheritance and interpolation, dependency management fills missing local
dependency fields. An explicit dependency version takes precedence, and
`optional` is not managed. Library-local management does not impose project-wide
version constraints on unrelated roots. Platform POM constraints retain their
separate existing semantics. These rules follow Maven's documented
[model construction order](https://maven.apache.org/ref/3.9.12/maven-model-builder/)
and [management injector](https://raw.githubusercontent.com/apache/maven/maven-3.9.12/maven-model-builder/src/main/java/org/apache/maven/model/management/DefaultDependencyManagementInjector.java).

Effective test/provided/optional dependencies are excluded from a consumer
closure; runtime dependencies are excluded from API usage. Exclusions attached
to these wholly excluded declarations cannot affect consumer artifacts. Selected
dependencies with exclusions, classifiers, or non-jar types fail. POM bytecode
compatibility remains unproven because POMs do not declare a producer JVM target.

Profiles may contain build/reporting/modules/properties only. Any profile
dependency, management, repository, or other unsupported model field fails.
If a resolution expression directly or transitively references a property that
any profile defines, resolution fails without guessing activation. Irrelevant
build-only properties can remain unused. Environment/system/settings properties,
management imports, unresolved expressions, mutable parent versions, relocation,
producer-specified repositories, and malformed/duplicate declarations remain
unsupported. This is a bounded published dependency model, not a Maven build
interpreter; build/plugin execution is never performed.

## Independent semantics and regression evidence

`tests/resolution/oracle` contains synthetic POMs and captured outputs from Apache
Maven model builder 3.9.16, plus a standalone Java reproducer. This oracle performs
only synthetic effective-model construction, without consumer builds or artifact
resolution. Native tests consume the same POMs and compare API/runtime results
to the captured outputs. The oracle confirms property override timing, whole
declaration replacement, management injection, scope defaults, and unmanaged
optional flags. See its README for exact commands and oracle jar digests.

Adversarial Native tests also cover wrong parent identities, ancestry cycles and
depth, aggregate component/request limits, unsafe relative paths, profile
influence through nested properties, unsupported imports/classifiers/exclusions,
duplicate/interpolated dependency identities, unrelated-root isolation, and
missing/corrupted parent bytes during lock replay.

Raw producer POMs for java-diff-utils 4.16 and Byte Buddy/agent 1.17.6, including
their parents, are retained under `tests/resolution/fixtures`; `manifest.json`
records exact repository URLs, hashes, and byte sizes. Their selected jars match
the existing kotaml reference exactly, with no test/provided dependencies added.

## Commands and retained results

```sh
python3 tools/test_resolution.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
python3 tools/test_kotaml_resolution.py \
  --resolve-command /Users/yoda/dev/pet/kvik/.kvik/tests/kotaml-cli/case-r6zd4237/resolve.command.json \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_dependency_locks.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
```

The resolver suite passes 22 Native tests and still matches all 12 production
artifacts against the KTC cache. All 11 Native lock integration tests pass, and
both persisted production/scoped locks validate against the v2 schema. Lock-suite
evidence is retained at `.kvik/tests/locking/case-eqcbwwpo` in this worktree.
Full Native probe evidence is retained at
`/private/tmp/kvik-k05-parent-pom/.kvik/tests/kotaml-resolution/case-0ccetjkb`:
`comparisons.json` has eight exact matches, `lock.json` retains parent provenance,
and `metadata.json` maps every fetched metadata URL to captured bytes/digests.
