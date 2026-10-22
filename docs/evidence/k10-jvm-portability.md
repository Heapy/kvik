# K10 bounded JVM repeatability and relocation audit

This audit runs the existing Kotlin/Native CLI and its owned JVM compiler workers
three times: A1 and A2 use one project root; B1 uses a different physical root
with a longer absolute path. All three have separate fresh action caches and
run directories. This is a same-host observation, not a two-machine test or a
remote-cache eligibility approval. Production compiler policy remains LOCAL.

## Observed result

All three forced builds passed at
`.kvik/tests/jvm-portability/case-xigzg6qd`. Each executed both compilation
actions. A1/A2 and A1/B1 have identical complete action specifications and exact
output manifests: five class/module files, 1,977 bytes in total. The final reread
of every output and each phase's CAS verification passed. No searched physical
root bytes were found. The four pair/action comparisons have no per-file
differences. `k10-jvm-portability-results.json` retains the observed manifests,
keys, tool identities and summary; it is evidence, not a normalization baseline.

Observed durations were A1 85.254 seconds, A2 87.818 seconds and B1 78.173 seconds,
including production full installed-tool verification. These are single-run
observations under local load, not a benchmark. Exactly 13 dependency metadata
and artifact blobs were seeded per fresh store. Three audit-helper tests passed.

Independent integration review re-read all three retained runs, recomputed the
complete action keys, output inventories and CAS digests, and checked journal,
completion and verification consistency against the tracked result. It confirmed
two executed actions and five files/1,977 bytes in every phase. The three helper
tests also passed independently. Review report:
`/private/tmp/kvik-k10-independent-review.json`. No new compiler run was needed
for this retained-evidence audit.

A preparatory attempt at `case-2bvcclf2` compiled A1 successfully but stopped in
the harness: it incorrectly included the JSONL newline when checking the final
event's canonical JSON digest. That assertion was corrected; the complete matrix
above ran from fresh directories afterward. The preparatory attempt is not
counted as the three-phase result. Initial runtime-provenance parsing fixes
occurred before it created build evidence.

## Fixture and identities

`tests/jvm-portability/fixture.json` pins the pristine cold-build snapshot from
`tools/test_build_cli.py`: a common/actual library and a dependent JVM consumer.
The harness reconstructs exact declared input bytes from retained CAS, using
the original `inputs.json`, configuration, lock and tools document. It does not
copy the subsequently edited live fixture: in particular, the later freshness
test's `library/common/New.kt` is absent. Tests/resources present in the snapshot
remain input evidence, but this audit invokes build and compiles only the two
production modules. It does not execute tests or compile test sources.

The fixed runtime composition is
`21e25288040f1c25dae89eab37312c77cf4604dd91f056ecf464b8b2dba22e48`.
Its executable SHA-256 is
`1e3445daa801f2524fa43bf294efffae24da05d910d35b760525ccd0e1300d57`
and size is 3,931,592 bytes. The harness checks both identities and the worker
bundle manifest identity, retains the complete runtime lock and tools document,
and checks the adapter source-tree digest against the runtime composition before
deriving physical worker command recipes from it. These recipes are labeled as
source-derived; no OS argv trace is claimed. Exact typed actions, input trees,
ordered worker requests, worker results and tool identities are retained per run.
The normal production provider performs fresh installed-tool verification.

Each store starts with only the dependency lock's digest-verified metadata and
artifact blobs. No source/output blobs, action records, worker home or worker
temporary state are seeded. Dependency bytes are copied, not hard-linked, from
the read-only existing store. Offline build never invokes resolve. Both action
statuses must be EXECUTED in every phase. Cold here means no action-cache reuse
and fresh workers; it does not mean cold filesystem caches or a fresh host.

## Comparison

For each action the harness checks its canonical key, complete specification,
exact output inventory and each retained/CAS output digest. Comparison includes
logical paths, raw SHA-256, byte size and executable bit. The output inventory
includes all class files and `.kotlin_module` metadata; timestamps and empty
directories are outside Kvik's artifact identity. A1/A2 checks repeat execution;
A1/B1 checks relocation. Each output is independently re-read after all runs.
No output bytes, paths or metadata are rewritten to make the comparison pass.

Differences retain added/missing paths, both file entries and the first differing
byte offset when both files exist. A diagnostic byte search checks output files
for project A/B, worker, store, runtime, JDK and compiler-artifact roots in UTF-8,
UTF-16LE and UTF-16BE. A match is retained, not normalized away. Absence of a match
does not prove absence of every possible encoded or transformed path.

Retained verification must be passed/current, the complete journal must match
stdout and its completion digest, and its final event must match the verification
record. Project inputs and original retained snapshot files must remain exact.

## Reproduction

From the isolated worktree, with retained root evidence available:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tests/jvm-portability/test_audit.py

/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_jvm_portability.py \
  --runtime /Users/yoda/dev/pet/kvik/.kvik/runtime/executables/21e25288040f1c25dae89eab37312c77cf4604dd91f056ecf464b8b2dba22e48/kvik.kexe \
  --seed-run /Users/yoda/dev/pet/kvik/.kvik/tests/build-cli/case-kcswfrtl/.kvik/runs/kvik-run-0oneIK \
  --seed-store /Users/yoda/dev/pet/kvik/.kvik/tests/build-cli/case-kcswfrtl/.kvik/cache
```

Each invocation creates a new `.kvik/tests/jvm-portability/case-*` directory.
`provenance.json` records pins and the environment boundary; each phase retains
`audit.json`, command, streams, original CLI evidence and raw output files.
`summary.json` records comparison results, path matches and per-phase durations.
Three helper tests cover same-size corruption, executable bits, missing output,
symlink rejection and literal path detection. They supplement the actual builds.

## Limits and remaining gates

The audited combination is Kotlin 2.4.20 BTA, the pinned Liberica JDK 25.0.4,
macOS arm64, default JVM target 11 with host JDK APIs, no compiler plugin, and the
small common/actual plus consumer source fixture. Explicit JDK release settings,
serialization plugins, test/friend-path compilation, broader language features,
other hosts and relocated tool installations require their own evidence.
Stable trusted tool roots and filesystem ancestors remain the existing contract;
this is not filesystem sandboxing or proof against undeclared host input reads.

REMOTE additionally requires an explicit typed policy/approval design scoped to
the audited combination, a complete logical REAPI command recipe, production
composition/executor integration preserving cancellation and hard cache-conflict
semantics, and measured transfer limits. Current default 1 MiB remote blobs are
too small for the pinned 1,853,314-byte stdlib. This audit uses no remote service,
does not change cache policy and does not complete K10 cross-machine gates.
