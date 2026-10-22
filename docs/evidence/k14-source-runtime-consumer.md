# Source-composed runtime JVM consumer gate

The existing `tools/test_build_cli.py` accepts `--runtime PATH` to execute an
already provisioned Native CLI directly. This mode neither queries nor falls back
to the checkout's bootstrap launcher. Omitting the option preserves the existing
`kvik --offline --no-build --print-runtime` lookup. `--artifact-cache PATH` selects
the compiler artifact cache in the tool installation; its default remains
`.kvik/tool-artifacts`. `--output-dir PATH` selects a parent for a new isolated
`case-*` fixture; its default remains `.kvik/tests/build-cli`.

The fixture and existing assertions are unchanged: two JVM modules, common/actual
sources, a module dependency and resource, real JUnit execution with a per-run
filesystem effect, cold build, two test runs, identical repeat action keys,
compiler cache hits with fresh tests, retained event/verification schema and
journal checks, and stale evidence after an added source. Dependency resolution
retains the harness's existing online Central resolution; build/test use the
resulting lock offline. This is the existing production consumer gate, not a
replacement source-bootstrap or transport test.

Each fixture also records `invocation.json` with the absolute runtime path,
runtime SHA-256 and size, explicit-runtime flag, artifact-cache path, harness
SHA-256, and tool-lock SHA-256. These are invocation provenance, not a claim of
runtime immutability after hashing. The supplied executable, bundle, tools and
artifact cache must remain stable throughout the run, under the production
provider contracts.

## Preparation checks

Using `/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python`:

```sh
python -B tools/test_build_cli.py --help
python -c 'import ast; from pathlib import Path; ast.parse(Path("tools/test_build_cli.py").read_text())'
git diff --check
```

No runtime was launched and no compiler was run for these preparation checks.
The actual run below used the parent-supplied source-composed executable after
the source graph gate passed and the coordinated compiler slot was released.
Exact command (working directory `/private/tmp/kvik-k14-source-consumer`):

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_build_cli.py \
  --runtime /private/tmp/kvik-k14-source-bootstrap/.kvik/tests/source-bootstrap/case-kgcfr28z/runtime-cache/executables/7db70e5516292ccfe1fefc2c015885beac0db801e8f3730425b78c976bdb9cae/kvik.kexe \
  --artifact-cache /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts \
  --output-dir /private/tmp/kvik-source-runtime-consumer-evidence \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20
```

The source graph build/acquisition gate remains separately required. This harness
alone does not prove remote source closure, plugin participation, cross-machine
portability, or completion of K14.

## Actual source-runtime consumer result

The full harness at commit `31c514df6ea2077f07a733f96715e787ea2527d7`
completed with exit 0 on 2026-10-22 (owned execution handle `43967`). No default
launcher query or source bootstrap occurred. Retained fixture:
`/private/tmp/kvik-source-runtime-consumer-evidence/case-kyjizwh6`.

Runtime SHA-256:
`3a07a8fce01e5d83639b46d3dd5c8f98bc730f6ee0bcd6189a1467b6dbe489d8`
(5,996,696 bytes). Composition identity is the `7db70e...` directory above;
the composition key and executable file digest are distinct. The invocation
receipt binds harness SHA-256
`489e2be478112a04cd55cad16f79ada563ff9d2a075cab7a1081e6c459864085`
and tool-lock SHA-256
`7e5391b3c8b18ca164c91b3e1ffd6207653eb88337b0965abf1822767cfbb57c`.

| Phase | Duration | Actions |
| --- | ---: | --- |
| Cold build | 4.907 s | Both module main compiles executed |
| First test | 3.523 s | Two main compile hits; test compile and test executed |
| Repeated test | 1.489 s | Three compiler hits; test executed again |

All completed run records were passed/current with zero failed tests. Both test
runs discovered and executed exactly the same passed JUnit identity:
`[engine:junit-jupiter]/[class:fixture.PassingTests]/[method:dependencyAndResource()]`
for `consumer` / `jvm`. The independent per-run work directories retain
`effects.txt` containing `executed\n`, proving the repeated test's execution.
Repeated action IDs/keys matched exactly. The common/actual implementation,
module dependency and resource assertions were exercised by that test.

`summary.json` retains every action key/output manifest and run path;
`invocation.json` retains runtime provenance. Each run retains its complete
`events.jsonl`, `verification.json` and completion marker; captured stdout is
byte-identical to its journal. `retained-audit.json` records a post-run audit of
all three journal digests, exact tests/effects and all eight distinct output CAS
blob hashes/sizes. The four copied journal mutations (corrupt prefix, deleted
middle, reordered history, final event only) each returned indeterminate/exit 3
with the committed-evidence mismatch reason. Adding a source then returned
stale/exit 3, retained in `stale-query.json`.

No runtime, runtime-cache or source-bootstrap fixture was modified by this gate.
This result proves this existing production JVM consumer works with the supplied
source-composed CLI; it does not establish all K14 acceptance criteria.

Integration independently rehashed the executable and all eight output blobs,
checked the three completion/journal/verification bindings and stdout equality,
exact test identities/reports and distinct per-run effects, and all retained
journal-corruption and stale-source queries. Report:
`/private/tmp/kvik-source-consumer-independent-audit.json`; the reproducible
read-only script has the same basename with `.py`. This was a retained-byte
audit, without rerunning compilers/tests or rehashing the complete installed
tool roots. A process check after both fixture handles terminated found no
remaining process referencing either exact fixture path.
