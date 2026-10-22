# K08 KSP artifact bounds through the public CLI

The CLI now uses `EffectiveConfiguration.maxArtifactBytes` for resolution
transport, writable CAS, read-only locked checks and build snapshot CAS. The
resolver still enforces each domain's own bound: only KSP runtime permits
128 MiB; ordinary dependencies and symbol processors retain 64 MiB. Configuration
capture remains bound to the initially read project digest.

The composed runtime passed all **11 dependency CLI tests** in 37.877 seconds,
including the complete upstream KSP/processor host closures: four root selections,
nine unique artifacts (87,168,977 bytes) and eleven metadata identities. The
80,992,357-byte analysis jar matches its pinned SHA-256. The fixture explicitly
passes `--pin org.jetbrains.kotlin:kotlin-stdlib:2.3.20` because upstream host
closures request both 2.2.21 and 2.3.20. Runtime KSP API 2.3.12 and processor API
2.3.9 remain in separate domains; no implicit conflict resolution was introduced.

Successful `check-lock` and `resolve --offline` preserve the entire fixture's
file hashes, sizes, modes and mtimes. A one-byte jar mutation with preserved size
and mtime is rejected without repairing the cache or rewriting the lock. The
same large package in an ordinary JVM domain is rejected by online `resolve`,
leaving the prior lock unchanged. Existing publication concurrency, scope pins,
alias protection, corrupt/missing/stale locks and offline no-write tests also pass.

## Transport diagnostic

The initial ordinary-domain negative returned libcurl error 56, which could not
by itself prove a size-limit failure. A direct C probe reproduced HTTP 200,
67,106,638 bytes received under a 67,108,864-byte cap, and an explicit callback
limit-exceeded flag. A refused connection returned error 7 with that flag clear.
The native boundary now preserves this flag and reports
`HTTPS response exceeds 67108864 byte bound` before interpreting curl's generic
error. Network errors retain a separate diagnostic. No download limit changed
in this correction.

`tests/resolution/transport_bound.c` checks exact-bound acceptance, a zero-length
write, refusal without changing received bytes, an oversized first chunk and
multiplication overflow. It passes independently and is included in the normal
resolver test runner. Independent source review approved both the CLI bound
wiring and the transport flag/error precedence.

## Reproduction and retained evidence

```sh
./kvik --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --konan-data /Users/yoda/.konan \
  --artifact-cache /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts \
  --offline --print-runtime
KVIK_DEPENDENCY_CLI_EVIDENCE=.kvik/tests/dependency-cli \
  /Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tests/bootstrap/test_dependency_cli.py
```

Observed checkout: `/private/tmp/kvik-k08-cli-integration`. Composition:
`b9634c6bfce0875f6f924cffec11e01d0ec8072c44f384545e8c9d7e80648d87`;
executable SHA-256 `0a064af83bf143fdd34c7ac53b29c44a829576863e6e3f3b5e6d6d79dbcf3255`,
4,141,624 bytes. The compile phase took 63,185 ms; this is an observation, not a
benchmark. The previous composition `6e3152e9...` established the positive KSP
path but failed the negative test's curl-code assumption; it is not the final gate.

The passing large-jar case is
`.kvik/tests/dependency-cli/test_native_ksp_large_artifact_replays_readonly_and_ordinary_domains_keep_their_bound-kilzrlwx/`.
It retains every command/stdout/stderr, before/after read-only inventories and
before/after corruption inventories. Final fixture state intentionally contains
the corrupted jar and an ordinary JVM project beside the unchanged KSP lock.
A retained audit matched all nine artifact identities, accounting for the exact
intentional first-byte mutation without modifying it. Full suite log:
`/private/tmp/kvik-k08-cli-dependencies-final.log`.

This proves public resolution and locked replay. It does not claim public CLI
KSP generation or Native Kotest dispatch; those adapters have separate evidence
and still require composed graph/CLI integration.
