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
Actual source-runtime consumer evidence is pending an independently approved
source-composed executable and the coordinated compiler slot. Intended invocation:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_build_cli.py \
  --runtime /absolute/approved/source-runtime/kvik.kexe \
  --artifact-cache /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts \
  --output-dir /private/tmp/kvik-source-runtime-consumer-evidence \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20
```

The source graph build/acquisition gate remains separately required. This harness
alone does not prove remote source closure, plugin participation, cross-machine
portability, or completion of K14.
