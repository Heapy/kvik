# K14 foreground source-lock launch context

The Native launch-context parser now accepts optional `--source-lock` followed by
optional `--source-cache`, after `--artifact-cache` and before `--offline` /
`--no-build`. It preserves literal absolute path spelling and argument order.
Cache without lock is rejected; lock without cache and legacy descriptors remain
valid. The maximum argument count is 16 (minimum 6); the 32 KiB UTF-8 response
limit is unchanged. Replacement descriptors must retain the exact original
arguments, including both source paths and offline/no-build policy.

The bootstrap-launch schema adds the two flags, the new count bound, and the
cache-requires-lock relationship. The Native parser remains responsible for the
complete ordered flag/value grammar, NUL rejection, and runtime/composition
binding. Session resume versions 1 and 2 retain their existing wire shapes.

## Reproduction

From this checkout, with the separately developed real Python producer:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_foreground_bootstrap.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --producer-root /private/tmp/kvik-k14-launcher
```

`--producer-root` defaults to this checkout after producer integration. It selects
an explicit source checkout; the harness records the producer file and function
hashes, checks fixture-generation stability, and rechecks the function after the
Native run. It does not copy or modify the producer. The compiler runs with a
constructed environment and offline Kotlin/Native dependency policy; no Gradle,
KTC, or compiler server is used.

## Actual result

Retained evidence:
`.kvik/tests/foreground-bootstrap/case-ofalr6p2/` contains both producer JSON
fixtures, generated Kotlin bytes, the exact command/environment and 16 source
hashes in `receipt.json`, compiler input hashes, and compile/test logs.

- Three schema acceptance cases passed: legacy, both source flags, lock only.
- Two schema rejection cases passed: cache without lock and oversized arguments.
- Nine Native tests passed in 6 ms; compile plus execution took 13.720 s.
- Tests cover literal paths, missing values, reordered/repeated flags, relative
  and NUL-containing paths, argument/byte bounds, changed replacement paths or
  omitted source policy, and both legacy/new descriptors in resume v1/v2.
- Resume assertions retain pending test intent, partial JSONL bytes for v1,
  terminal lease bytes for v2, and exact subsequent launch argv.
- Native binary SHA-256:
  `21c4a0294af6b6812903bc065d3df79dc21217107a68cbac8be1fe44b747836e`.
- Test log SHA-256:
  `38a571a14ffb19de24d252023a719e5ad584f27640e1c6a1e66c3d3dff588016`.
- Producer function SHA-256:
  `055cda0fafe0356f30dc49470773cb11fdaef40c3bb5541d62d86f4b916342a6`.
- Producer file SHA-256, unchanged across the run:
  `da8803344cd296956c0ae6952f74a40dc125bb8b4684868f4d234aa978b539b3`.

An earlier run, `case-vwwqiwri`, failed six existing/new legacy-compatibility
assertions because an intermediate edit accidentally required both source paths.
Its receipt and logs remain retained. The corrected run above alone establishes
the passing result.

This is a producer/Native-consumer contract gate. It does not acquire remote
sources, execute a new full CLI composition, or exercise a live process handoff
using the new flags. Those integration gates belong to the source acquisition
and launcher work. The v2 lease here is a structural continuation fixture, not a
new terminal restoration test. Installed tool roots are assumed stable during
this focused run; the receipt is not a complete installed-tool inventory.
