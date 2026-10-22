# Bounded Gradle preferred-version metadata

The public Native kotaml resolve reached an atomicfu 0.32.1 dependency whose
version object contains `prefers`, without `requires`. The old parser rejected
it with `Missing fields: [requires]`. This change supports that exact form without
converting a soft preference into a required minimum or silently selecting a
version according to traversal order.

The accepted version object contains exactly one literal `requires` or
`prefers` field. Existing required-version behavior is unchanged. A prefers-only
dependency requires an explicit effective global/scoped pin; it can select below,
equal to, or above the preference, but all independently reached requirements
and constraints still apply. A no-pin case fails with an actionable unsupported
selection error, even if the same coordinate was visited as an earlier root.
Prefer-only constraints do not introduce dependencies and do not impose a floor
on a selected version. This is a deliberately bounded subset: mixed rich-version
fields, dynamic versions, ranges, mutable snapshots, strict constraints and
rejection lists remain unsupported.

Gradle's [versioned 8.12.0 metadata specification](https://github.com/gradle/gradle/blob/v8.12.0/platforms/documentation/docs/src/docs/design/gradle-module-metadata-latest-specification.md)
defines separate optional `requires` and `prefers` fields for dependencies and
constraints. Its [rich-version documentation](https://docs.gradle.org/current/userguide/rich_versions.html)
identifies preference as weaker than a required or strict declaration. Kvik's
explicit-pin requirement restricts automatic selection further; this change
does not claim Gradle conflict-resolution compatibility.

Queries preserve the raw preferred coordinate and `semantics: "prefers"`, with
the actual selection in the separate `selected` field. Required constraints keep
their existing `constraint` semantics. Redirects and their capability/ownership
checks are unchanged. Explicit metadata exclusions still match the exact raw
coordinate before pins; a preferred constraint cannot be excluded as if it were
an ordinary dependency. No core model, lock shape or effective-policy identity
changes were needed; old successful requires-only resolutions keep their meaning.

## Exact producer evidence

Two unmodified published metadata files were copied from the pinned KTC metadata
cache into `tests/resolution/fixtures`, with their Maven Central URLs, SHA-256
and sizes added to the existing manifest. The native producer is
[atomicfu-macosarm64 0.32.1](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/atomicfu-macosarm64/0.32.1/atomicfu-macosarm64-0.32.1.module):
`650c1dc58d4c9dca48b8bf3d575545e073193d73287ebbee820bec6a7a962728`,
4,162 bytes. Its selected `macosArm64ApiElements-published` variant declares
`org.jetbrains.kotlin:kotlin-stdlib` with `prefers: "2.2.0"`.

The actual test resolves the root `org.jetbrains.kotlinx:atomicfu:0.32.1` redirect,
selects stdlib 2.4.20 by explicit pin, and verifies both published Native files:

| Artifact | SHA-256 | Bytes |
| --- | --- | --- |
| atomicfu-macosarm64-0.32.1.klib | `368865987bfa5da765e40b15b4df60fd12f257688ed65a96ca71ebf2cf1a5261` | 83073 |
| atomicfu-macosarm64-0.32.1-cinterop-interop.klib | `165c584c77f7c716cdc5a083093ef235c9bacfeabad6ed32ca9f7b928acbe292` | 3545 |

Stdlib's published `nativeApiElements` variant has no external file; its metadata
is still selected, hashed and replayed. No JVM artifact is substituted and no
atomicfu source or binary is executed. This focused proof does not yet establish
full public Native kotaml resolution or execution parity.

## Validation

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_metadata_exclusions.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -m unittest discover \
  -s tests/package-queries -p test_schema.py
python3 tools/generate_package_schema.py
```

All 65 Native groups passed: 58 existing resolver, lock, package-query and explicit
metadata-exclusion groups, plus seven preference groups. Five Python schema
checks passed, and generation retains the checked-in preferred-edge schema.
The original build attempt failed only on two new test-helper syntax errors;
the corrected full build/run passed. Tool roots were held stable throughout.

Tests cover pins below/equal/above the preference; actionable no-pin refusal;
required floors and both root orders; inactive/active soft constraints; malformed
and unsupported version forms; raw exclusion matching and constraint refusal;
redirect identity; actual atomicfu artifacts; serialized lock replay; project
query provenance without transport calls/CAS writes; and missing, corrupt or
stale lock inputs. The combined runner also verifies its existing 12 artifact
identities and schema-negative cases.

Evidence is retained in `/private/tmp/kvik-k05-preferred-versions/.kvik/tests/metadata-exclusions/case-dpqz39ve`:

- Native binary SHA-256: `b419c3bbcb5efaa7c15ebb81173c610610a361416a2459765dbe03602eb6885a`.
- `preferred-lock.json`: `b81f11c5585b6c9027d66818b6d2c8d99eb106004ba90da062a8f68ee2835730`.
- `preferred-project.json`: `826fc5ff0ad90b4e4d2dcfd519534cc5de858c648b35fe118d52c1ac63b110a8`.
- `preferred-constraints.json`: `92ce0b7d92922400c68f229d4fc637dd0ba244a097eae6797ceae6134bbf3cc5`.

The query records the original stdlib 2.2.0 preference separately from selected
2.4.20 and retains the atomicfu redirect and unused stdlib-common constraint.
