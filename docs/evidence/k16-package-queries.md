# K16 package and dependency queries

The v1 contract and independent JSON schema are in
`integration/package-queries/CONTRACT.md` and `schemas/v1/packages.json`.
The implementation provides all three command boundaries: repository versions,
package dependency graphs, and read-only project dependency graphs. These are
queries; available versions and selected metadata do not establish compatibility.

`MavenResolver.resolveGraph` uses the same traversal and checks as `resolveAll`.
Trace collection is enabled only for graph queries. Ordinary lock resolution does
not acquire the query node/edge/output restrictions or allocate a full trace.
The trace distinguishes declared/effective requirements from pins, redirects,
platform constraints and edge-local nontransitive expansion. Paths are bounded
shortest paths per root/node, with the complete bounded edge list retained.

Project queries verify the entire v2 lock and every locked CAS identity, then
replay selected domains using exactly those bytes and their domain pins. Replayed
selections must equal the lock before paths are emitted. Display filters cannot
hide corrupt unselected inputs. Module visibility/runtime-only declarations and
KSP JVM-host domains remain distinct; they are not synthetic producer attributes.

Repository queries use a separate marked query-cache directory, strict bounded
observation records, atomic record publication and digest verification on every
read. Offline mode cannot fetch, create, repair or write. A stale verified
observation remains explicitly stale. Default online reuse lasts one hour;
refresh is explicit. Version ordering is lexical, and unknown qualifier forms
remain unclassified in the default `all` channel. Pagination binds the package,
repositories, channel/page size, metadata observation snapshot, offset and expiry.

## Validation

The focused Native suite uses the provisioned Kotlin/Native 2.4.20 compiler and
JDK 25.0.4, libxml2 for XML and libcurl for HTTPS. It compiles production query
services plus the actual CLI query functions into a small Native command probe;
it does not invoke Gradle, Kotlin Toolchain or JVM build orchestration.

Reproduction after integration:

```
.kvik/schema-tools/bin/python tools/test_package_queries.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache --online
python3 tools/test_resolution.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
.kvik/schema-tools/bin/python -m unittest discover -s tests/package-queries -p test_schema.py
```

The isolated branch precedes the root-owned CLI artifact-cap wiring commit. Its
probe compilation uses an ignored, byte-identical copy of the reviewed
`Dependencies.kt` through `--dependency-cli-source`:

- `.kvik/tests/package-queries/Dependencies.kt`, 7,170 bytes;
- SHA-256 `6c28a782b9a4f58aae95b9fa6389694ab3171694b4fe66aa06dc04f68f1fc5bc`.

That file is a test compilation input, not a K16 production modification. The
final source composition must include `integration/package-queries/src`; the
root owns that bootstrap integration and the composed-command gate.

Final retained result: `.kvik/tests/package-queries/case-fmbc8o_t/summary.json`
in `/private/tmp/kvik-k16-package-queries`. The full command (including compilation)
returned 0 with 13 Native groups, 13 schema-validated service results and 15 CLI
probes. Its exact argv lists are retained in `commands.json`; stdout/JSON and stderr
for each CLI probe are retained beside the summary. The final build used the
`--dependency-cli-source` path above and `--online`.

The existing resolver regression separately returned 0: 27 Native groups and all
12 selected artifact identities matched the KTC cache. Logs are retained at
`/private/tmp/kvik-k16-queries-final.log` and
`/private/tmp/kvik-k16-resolver-regression.log`. Five independent package-schema
unit tests and Python syntax checks also passed. No composed public runtime gate
is claimed by this isolated probe evidence.

The 13 Native groups cover:

- Original requirements versus explicit pins, deterministic shortest paths,
  redirects, BOM constraints, and both encounter orders for edge-local wildcard
  exclusions.
- A graph exceeding 8,192 edges reports incomplete/bounded query evidence while
  ordinary lock resolution still returns its original closure.
- Actual pinned JUnit 5.13.4 API/runtime metadata/BOM closures, exact expected
  artifact hashes, serialization 1.11.0 JVM redirect selections and Native
  metadata selections using existing captured metadata and KTC cache bytes.
- Main annotations 13 versus scoped test annotations 23, exported/private module
  dependencies, runtime-only dependencies, explicit KSP/processor host purposes,
  and no writes during project replay.
- Missing, corrupt and stale locks/CAS, including corruption outside the display
  filter; malformed/expired/mismatched cursors; multiple repository provenance;
  unknown XML/DTD/namespace/identity; stale and missing offline observations;
  same-query origin preservation; corrupt disk observations/blobs and explicit
  refresh repair.

The Python boundary checks validate every retained query JSON against the v1
schema. They compare complete file inventories, content hashes, modes and mtimes
before and after offline/project queries. Online checks fetch Central version
metadata and the pinned JUnit runtime graph, then require identical graph data on
offline replay with unchanged project and lock bytes. Version XML fixtures carry
URL, observation time and exact SHA-256/size provenance in
`tests/package-queries/fixtures/provenance.json`; upstream metadata is mutable,
so the fixture is an observation rather than a claim about today's newest release.

## Limits

This is the existing bounded K05 Maven/Gradle Module Metadata subset, not generic
Maven/Gradle compatibility. Dependency queries verify artifact bytes and can be
more expensive than metadata-only listing. Paths do not enumerate all possible
routes. Project declarations are separate from external metadata paths; no
source-set/module trail is invented from a flattened lock. Overlapping repository
URL bases are refused when dependency metadata provenance is ambiguous. Version
channels are the documented numeric/known-qualifier convention, not Maven version
precedence or an upgrade recommendation. POM-only metadata does not prove JVM
bytecode compatibility. No consumer source compilation or package code execution
is performed by a query.

Independent review found and reproduced one schema-only issue: a trailing newline
was accepted by end-anchored coordinate/hash patterns despite runtime rejection.
The generator now uses an absolute-end assertion for package, coordinate and
digest patterns, with newline/CR/CRLF regression cases. All retained positive
query outputs still validate. Runtime query sources and binaries are unchanged.
