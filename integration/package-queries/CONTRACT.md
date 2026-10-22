# Package query protocol v1

K16 exposes three JSON-only query commands. `--json` is accepted explicitly;
omitting it still returns the same versioned JSON. They never execute package
code or publish project configuration/dependency locks.

```
kvik packages versions GROUP:ARTIFACT --project kvik.json --json
  [--channel all|stable|prerelease] [--limit 1..200] [--cursor TOKEN]
  [--query-cache PATH] [--refresh|--offline]
kvik packages dependencies GROUP:ARTIFACT:VERSION --project kvik.json --json
  --target jvm|macosArm64 --usage java-api|java-runtime|kotlin-api
  [--jvm-target 11|17|21|25] [--pin GROUP:ARTIFACT:VERSION ...]
  [--query-cache PATH] [--refresh|--offline]
kvik dependencies --project kvik.json --json
  [--lock PATH] [--store PATH] [--pin ...] [--scope-pin ...]
  [--module ID] [--target jvm|macosArm64] [--compilation main|test]
  [--usage java-api|java-runtime|kotlin-api|kotlin-runtime] [--offline]
```

Repositories come from the validated project in declared order. No implicit
Central repository is added. Version listing queries every configured repository;
package selection retains K05's first-found repository policy. `--refresh` and
`--offline` are mutually exclusive. Bootstrap offline mode also prohibits query
network access. JVM target defaults to the existing explicit consumer policy 11;
Native queries reject `--jvm-target` and require `kotlin-api`. Version queries do
not accept pins. Project queries accept the existing resolver policy/pins and
refuse a stale or mismatched lock; filters never change that lock's configuration.

## Envelope and status

`schemas/v1/packages.json` is the authoritative shape. Every result contains
`schemaVersion:1`, `kind`, `status`, `query`, `observedAt` (UTC RFC3339),
`freshness`, `freshnessBasis`, `sources`, `diagnostics`, and
`compatibility:"not-assessed"`, followed by the command-specific payload.

`status` is `complete`, `incomplete`, or `unsupported`. Exit 0 requires complete
evidence; incomplete and unsupported query results exit 3. Invalid command or
project syntax uses the existing CLI diagnostic/exit 2 contract. Transport/cache
failures become explicit incomplete query evidence; unsupported resolver semantics
remain explicit unsupported evidence, never a guessed dependency graph.

`freshness` is `fresh`, `stale`, `missing`, or `indeterminate`.
`freshnessBasis:repository-observation` describes repository bytes observed at a
time, not an assertion that the remote repository has remained unchanged.
`freshnessBasis:locked-inputs` means exact current project/configuration/lock/CAS
agreement; it does not imply that locked versions are the latest available.
Per-source records retain repository ID, URL, metadata/artifact role, observed
digest/time when known, cache origin, freshness and failure status. A 404 is a
repository observation; an absent offline cache entry is not evidence of a 404.

## Versions

Default channel is `all`, default page size 50. Version strings are deduplicated
across repositories, with all provenance repository IDs retained per row. Ordering
is explicitly `lexical-ascending`, not Maven/Gradle version precedence. No result
is called “latest”. Repository `latest`, `release` and `lastUpdated` declarations
are informational source fields, not selection or compatibility policy.

Classification is a bounded query convention (`numeric-known-qualifiers-v1`):
numeric dotted versions, optionally suffixed final/ga/release, are stable; known
alpha/a, beta/b, milestone/m, rc/cr, preview/ea/eap, dev and snapshot qualifiers
are prerelease. Unknown forms are `unclassified`, retained by `all` and excluded
from both narrower filters. This convention is not a Maven ordering algorithm.

Cursors bind the package, configured repositories, channel, complete metadata
snapshot digest, offset and expiry. Refreshing/changing metadata cannot silently
continue an old page. Malformed, expired or mismatched cursors return incomplete
evidence with a specific diagnostic. No offset-only pagination is accepted.

## Package dependencies

An additive `MavenResolver.resolveGraph` shares K05's exact traversal. Existing
`resolveAll` output and lock semantics remain unchanged. The graph retains roots,
selected nodes, dependency/redirect/constraint edges, effective requirements
before explicit pins, selected coordinates, variants, metadata identities and
edge expansion state. Effective inherited/interpolated POM requirements are
identified as such; raw property expressions are not represented as literal
version requirements. A constraint may have no selected target and never creates
a fictitious dependency selection.

Literal Gradle `prefers` declarations retain `requirement.semantics="prefers"`
and their original coordinate; `selected` remains separate. A prefers-only
dependency requires an explicit effective version pin in this bounded resolver.
That pin may be below or above the preference, subject to independently reached
required dependencies/constraints. Without a pin the query reports unsupported
selection rather than inferring a version from traversal order. Prefer-only
constraints neither create a selection nor impose a minimum; they may have a
null target. Mixed rich-version fields, ranges, mutable preferences, strict
constraints and rejected-version lists remain unsupported. Existing `requires`,
POM and redirect semantics are unchanged.

Nodes distinguish platform/library and expanded/nontransitive visits. Thus a
wildcard-excluded edge cannot suppress a normal path reaching the same component.
Paths are one deterministic shortest path per root/reachable node; all graph
edges remain visible, so alternative paths can be derived. Paths do not claim
enumeration of every possible route. Artifact verification is `verified`: the
initial implementation uses the existing resolver's bounded artifact downloads
and digest checks, including POM artifacts, rather than inventing identities for
unfetched artifacts. Neither fetching nor inspecting bytes executes package code.

## Project dependencies

The response separates all source-set/plugin declarations from effective scoped
root requests and selected locked graphs. Each domain identifies module, target,
main/test compilation, usage and dependencies/compiler-plugin/ksp-runtime/
symbol-processor purpose. Host-tool variants stay distinct from consumer targets.
Native test-setting dependency declarations are identified separately. Optional
filters restrict reported domains, not lock verification or declarations.
Declared module links and external metadata paths are distinct: external paths
start at a domain's verified root request, not an invented source-set/module edge.

The entire lock is strictly parsed and verified against current effective
configuration and existing CAS. Selected domain graphs are replayed with the same
pins from those exact locked metadata/artifact bytes, then their selections are
compared to the lock before paths are emitted. There is no network transport,
query-cache creation, repair or write on this command. Overlapping repository URL bases are rejected for dependency graph queries when a metadata source cannot be attributed uniquely; version listing keeps its explicit per-repository provenance. Missing/corrupt/stale locks
produce incomplete evidence while preserving the available declarations. Project
and lock bytes are checked again before successful completion.

## Cache, bounds and exclusions

Package queries may write only an explicit query cache (default
`.kvik/package-queries`), disjoint from project and lock files. An existing nonempty cache directory must carry the query-cache marker; build CAS directories are not adopted implicitly. Content is digest
verified on every replay; no mtime trust is introduced. URL observation records
are bounded, strictly parsed and atomically replaced. Default repository
observation TTL is one hour: online queries reuse fresh cache or fetch absent/stale
entries; refresh forces fetching. Offline queries never fetch, may expose verified
stale observations with `freshness:stale`, and identify missing/corrupt entries.
Corrupt cache evidence is not silently repaired except by explicit refresh.

Bounds: 32 repositories, 1 MiB/10,000 XML nodes per metadata document, 10,000
distinct listed versions, at most 200 rows/page; existing resolver 256 components,
1,024 requests, 64 MiB/artifact and 256 MiB aggregate bytes (locked project KSP-runtime domains retain their explicit 128 MiB policy); graph/path/output
bounds (1,024 nodes, 8,192 edges, 4,096 paths per graph, 256 reported project
domains, 16 MiB total JSON) are enforced before publication. Truncation is explicit incomplete
evidence, never an apparently complete shortened graph. `truncated` describes bounded query output; a transport byte-limit failure instead reports incomplete evidence with an explicit diagnostic and no dependency graph, without publishing partial artifact bytes. Unsupported Maven/KMP
semantics retain the resolver's existing fail-closed behavior. Available versions
are not a compatibility, support, vulnerability or upgrade recommendation.

Primary semantics: Maven's [A-level repository metadata](https://maven.apache.org/repositories/metadata.html)
defines version discovery. The [Maven version order specification](https://maven.apache.org/pom.html#Version_Order_Specification)
is more general than this command's explicitly lexical display order and bounded
channel labels; these queries do not replace Maven's ordering with an implicit
semver algorithm.
