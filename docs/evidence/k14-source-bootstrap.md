# K14 full source-graph launcher gate

This harness exercises the real trusted launcher with a remote source graph. It
owns only fixture/test code; it does not replace production HTTP openers, modify
system trust, upload repositories, or publish remotes.

## Fixture and implemented gate

`tools/test_source_bootstrap.py` captures the 85 regular files selected by the
current Kvik example lock into a deterministic tar.gz archive. Its two additional
BuildPlugins depend on one shared helper. The helper calls the actual
`kotlinx.serialization.json.Json.parseToJsonElement` API; both plugins expose its
value in their runtime-info configuration schema. A second pinned helper archive
changes that value from `source-v1` to `source-v2`.

The Native serialization JSON/core artifacts, metadata pins, provided stdlib and
library order are checked against the separately verified source-store fixture:
`/private/tmp/kvik-k14-source-store/.kvik/tests/source-store/case-rzkf8dpt/full-kvik/source.lock.json`.
Those artifact URLs remain the real Maven URLs. No archive contains executable
build scripts. Original Kvik path selection and runtime/support/JVM worker roles
are retained.

An in-process HTTPS listener serves the pinned archives on localhost. Its socket
and worker threads belong to the harness invocation; normal teardown closes and
joins the listener. An ephemeral private CA is bundled with the current public
roots, and only subprocess environments receive `SSL_CERT_FILE`. Neither the
harness process nor the system trust store is modified. The certificate lifetime
is two days; an expired retained case requires new fixture preparation.

The full gate is implemented to check:

1. Cold production HTTPS source acquisition and a source-built Native runtime.
2. Warm `--offline --no-build` reuse with nonexistent Java/Native compiler paths.
3. Relocation of the trusted bootstrap, source lock, runtime cache and source
   cache, followed by the same offline/no-build reuse.
4. Offline compilation into a new empty runtime cache using the relocated
   source cache; the HTTPS server rejects requests and request counts must stay
   unchanged across all offline phases.
5. A changed helper pin producing a changed composition, real recompilation,
   and `source-v2` observed in both plugins. Only the new helper archive may be
   requested from the fixture server.

The audit binds the published executable/guardian and all acquisition, input and
command receipts to the manifest. It verifies the complete source graph, exact
selected source files and roles, metadata/artifact digests, exact ordered Native
and JVM worker Kotlin argv, generated Registry.kt, and complete internal/external
KLIB order. Trusted launcher bytes are copied to a retained per-attempt directory
and rechecked. Commands, constructed environments, stdout/stderr bytes and hashes
are retained. Installed-tool identities are retained by the actual runtime lock;
the harness does not substitute its own tool verification.

## Reproduction

Light checks:

```sh
python3 tests/source-bootstrap/test_fixture.py
python3 tests/source-bootstrap/test_audit.py
python3 tools/test_source_bootstrap.py \
  --producer-root /private/tmp/kvik-k14-launcher --prepare-only
```

After the launcher/store candidate is approved and the compiler slot is granted,
continue a prepared case, or omit `--case` for a fresh fixture:

```sh
python3 tools/test_source_bootstrap.py \
  --producer-root /private/tmp/kvik-k14-launcher \
  --case /private/tmp/kvik-k14-source-bootstrap/.kvik/tests/source-bootstrap/case-kgcfr28z
```

`--case` reuses the exact pinned localhost port, archives and CA; it fails if that
port is unavailable. Every attempt retains separate command logs and summary.
It is intended to continue a preparation before the first full build, rather
than silently treating an existing runtime cache as a cold result. The artifact
cache is the provisioned `/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts` by default;
its path and the pinned Java/Native roots can be specified explicitly.

## Verified preparation

Final preparation on 2026-10-22 passed in:
`.kvik/tests/source-bootstrap/case-kgcfr28z/attempt-8p15je6k/`.

- Four actual fixture tests passed (0.783 s): deterministic archives and executable
  modes, mutation identity, selected-link refusal, and subprocess-only CA trust
  plus listener cleanup.
- One audit test passed (0.009 s): a synthetic baseline receipt was accepted;
  seven deliberately mutated receipts were rejected (extra/omitted Native input,
  extra JVM worker input, reordered libraries, removed source role, wrong metadata
  digest, wrong source-graph digest). These receipts are explicitly synthetic;
  they are not compilation evidence.
- The private CA was rejected without the bundle and accepted with it. The real
  public Maven serialization-core module metadata matched its pinned digest.
- The real launcher's `--print-composition` succeeded without creating runtime or
  source caches. The listener closed, and `compiledRuntime` is explicitly false.
- Summary SHA-256:
  `7325d7f9157193883ffe0ba0c7c8cab16630bd5e7293cf84914ebbc9596bd49e`.
- Harness SHA-256:
  `52dab4e39c88a2c5d71fb3d052b0ee3bf96c6419c260e20db4becf20abda2ce3`.
- Actual copied producer `bootstrap/launcher.py` SHA-256:
  `38932d2ad7e6f27382fb62a12188dd0218fbe2433052c0e99e64706c19a183ce`.

Earlier preparation attempts remain retained, including a strict-certificate
extension failure and a fixture launcher-mode-copy assertion; neither was a
compiler run.

## Actual full launcher result

The unchanged frozen harness `9d65b67` continued that case against the approved
launcher/store checkout. Handle 41792 completed with exit 0. The final retained
attempt is:
`.kvik/tests/source-bootstrap/case-kgcfr28z/attempt-pz7voq2r/`.
All commands/logs, both source-lock versions and the exact trusted bootstrap copy
remain there. `summary.json` records `passed=true`, `compiledRuntime=true`, and
`listenerClosed=true`; its SHA-256 is
`0bec297668cf2c0d88444cd24e1612d4950733ea2f785b3be862b5cf8a5d547b`.

| Actual phase | Wall seconds | Result |
| --- | ---: | --- |
| Cold HTTPS acquisition + compile + runtime-info | 78.518 | Both plugin schemas report `source-v1` |
| Warm offline/no-build, nonexistent compiler paths | 0.136 | Verified runtime cache hit |
| Relocated launcher/source lock/source+runtime caches | 0.230 | Offline/no-build hit with nonexistent compiler paths |
| Offline rebuild into empty runtime cache | 70.356 | Both schemas report `source-v1`; zero fixture HTTPS requests |
| Helper-pin mutation + compile + runtime-info | 73.339 | New key; both schemas report `source-v2`; only helper-v2 archive fetched |

Each of the three builds retained eight actual C/interop/JVM/Native command
records and 92 exact composition inputs: 85 Kvik files, three plugin/helper files,
two external KLIBs, and two metadata files. Their role-bound Kotlin command lines
contain exactly 76 Native Kotlin sources plus generated Registry.kt and 14 JVM
worker Kotlin sources. The external library suffix is exactly serialization-core
then serialization-json. Every acquisition/input/command receipt and executable
was rehashed against its published manifest by the harness.

Initial and offline-rebuilt composition:
`2afbf24d4322153309cd0ca3c0acb6a254d65de61482ed14cd95de6a04bba401`.
Mutated helper composition:
`7db70e5516292ccfe1fefc2c015885beac0db801e8f3730425b78c976bdb9cae`.
The initial cache is `case-kgcfr28z/runtime-cache`; the relocated offline build is
under `case-kgcfr28z/relocated/offline-rebuilt-cache`. Both retain their executable
manifests and all three source receipts under `executables/<composition>/`.

Native compile/publication durations were 44.337, 43.536, and 44.539 seconds;
these are local observations, not benchmarks. Initial executable SHA-256 is
`6a338b50223488b8cfee5c3e4cdae319ad70ce914f65ab7a8c9f1b126644ba47`;
offline rebuild is
`38bba97a1d066ef2b741bdd4db0d0c2af015618a7edf4a23fc91c002cad3ea44`;
mutated helper is
`3a07a8fce01e5d83639b46d3dd5c8f98bc730f6ee0bcd6189a1467b6dbe489d8`.
The equal-key cold and offline rebuilds have different binary bytes/sizes, so
this gate does not claim bit-reproducible Native linking or resolve the existing
K10 output-path/nondeterminism limitation.

The in-process HTTPS listener stopped after completion; a separate connection
check to its retained port failed with connection-refused. A later process-name
probe matched a newly started, separately owned JVM consumer smoke using the
resulting runtime; those processes were left untouched. This receipt claims no
consumer test result. The public Git transport gate remains the separate
source-store evidence. No full-gate build failed or was replaced by an older
runtime.

Independent review approved harness `9d65b67` and this result after checking
the five archives, selected source bytes, copied trusted bootstrap, all three
runtime publications, 24 build commands, 276 input records, worker bundles,
ordered library/compiler classpaths, source caches and retained HTTP requests.
The four fixture tests and adversarial receipt test also passed independently.
Report: `/private/tmp/kvik-source-bootstrap-independent-9cpwujay/report.json`;
reproduce the retained-byte audit with
`/private/tmp/kvik-source-bootstrap-independent-audit.py --final`.
The review bound installed-tool identities but did not repeat full installation
hashing or compiler execution. The resulting helper-v2 runtime also passed the
separate [production JVM consumer gate](k14-source-runtime-consumer.md).
