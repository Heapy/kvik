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

## Verified preparation, full compilation pending

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

The cold/warm/relocation/offline-rebuild/mutation phases have **not yet run**.
There is no production JVM consumer result or public Git transport claim here.
The independently tested source-store Git gate is separate. Earlier preparation
attempts remain retained, including a strict-certificate-extension failure and
a fixture launcher-mode-copy assertion; neither was a compiler run.
