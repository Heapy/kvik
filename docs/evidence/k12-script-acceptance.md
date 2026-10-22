# K12 public script acceptance gate

The acceptance harness is `tools/test_scripts_cli.py`; fixture and scope details
are in `tests/script-cli/README.md`. It was prepared in an isolated worktree based
on CLI candidate `f80a6f3`. Runtime/compiler/executor production code is outside
this harness ownership.

The first six light Python checks pass. Retained receipt and logs:
`/private/tmp/kvik-k12-script-acceptance/.kvik/tests/scripts-cli/light-eo3b0zrd`.
The receipt hashes the exact harness, fixtures and test source plus stdout/stderr.
The checks validate all three fixture documents, verify unchanged K13 fixture
copies, prohibit subprocesses during prepare-only, check exact ordered lock
classpath selection, and challenge journal/key/cache/runtime substitutions and
empty-directory/content mutation detection.

An earlier prepare-only case is retained at
`/private/tmp/kvik-k12-script-acceptance/.kvik/tests/scripts-cli/case-h7xgbk62`.
It exercised fixture/schema preparation only, before the final harness additions.
It is not an actual CLI acceptance result.

At initial harness preparation all actual phases were pending. The completed
results below now prove the three bounded phases. The public fixture asserts
exact BMP and astral PrintStream output and uses the separately reviewed executor
UTF-8 correction `8de6d41`. It preserves the earlier composed-runtime failure
that exposed ASCII replacement as historical evidence.

Independent preflight review found four harness proof gaps before any actual
phase ran: ordinary terminal cleanup allowed an eventual wait, harness timeouts
lost partial streams, the final receipt omitted some observation/ownership files,
and a differently named executable could be invoked beside the hashed runtime.
The follow-up requires immediate observed cleanup for ordinary terminal results,
retains timeout binary streams and explicit timeout/cleanup receipts, binds every
phase file in the final summary, and requires the invoked `kvik.kexe` filename.
Normal script process results now explicitly require `setupFailure: null` under
the root-owned process diagnostic extension.

Nine light tests pass after these corrections; source/log receipt:
`/private/tmp/kvik-k12-script-acceptance/.kvik/tests/scripts-cli/light-review-x9ixz3ur`.
The added tests independently challenge a still-live child at ordinary terminal,
partial invalid-UTF8 timeout streams, the unbound sibling executable and changes
to requests/observations/action specs/process evidence. They use synthetic data
and mocks; actual process cleanup is proved separately by the public phase below.

The first actual-phase invocation stopped in harness identity preflight before
any CLI/process-table/consumer invocation. The launcher stores `runtime.lock.json`
with a final newline while composition hashes canonical JSON. Case `case-qm0han4i`
retains this failure; it is not an acceptance run. The correction compares the
canonical parsed lock to composition and separately retains the exact raw digest.
Nine light tests, including newline acceptance and semantic-change rejection,
pass with receipt `light-canonical-_cjwd7r_`. A fresh explicit retry is required.

The next attempt, `case-iwml830f/standalone-bi6pps24`, resolved and completed its
cold CLI invocation successfully (8.697 seconds, exit 0). The harness then failed
its digest comparison: Python abbreviated a literal argv newline as `\n`, while
core canonical JSON spells it `\u000a`. The corrected core encoder covers all C0
characters and UTF-16 key order, preserving literal escaped backslashes; bootstrap
composition retains its separate Python encoder. Ten light tests pass at
`light-core-canonical-qs35nx3t`. Read-only re-audit of the original cold run passes
all completion/action digests and exact argv/environment/NUL/BMP/astral streams;
`cold-core-canonical-re-audit.json` binds that result. The original failure remains.
This proves only that cold run; warm/error/selector/lifecycle phases had not run.

## Completed standalone public gate

Frozen harness `c17882c` completed the standalone phase on 2026-10-22; handle
`39939` exited 0. The actual runtime composition was
`d87e052a81fae1f63f0d5c4958edc4b2a5d2e1ebf1baa7c4c81fed1cd7909075`,
executable SHA-256 `db7519af140d30460f8e380ec316d15d912741976e832096fe873145565e9ef6`
(5,585,096 bytes), built from root candidate `26661c2`. No runtime source was
changed during acceptance.

The 35 real CLI commands produced 11 complete run records. Cold invocation took
4.348 seconds and warm invocation 1.053 seconds under the current host load, not
as a benchmark. Warm compilation was a local hit; script execution stayed fresh
with distinct private directories and a second effect. Exact NUL, BMP and astral
stdout/stderr, literal argv including a newline and `@` prefix, closed stdin,
declared environment, absent ambient JVM options and project cwd passed. Ordinary
exits 125, 127, 130 and 255 remained failed execution records with the exact child
exit, not cancellation or setup failure. No/multiple/explicit/missing entrypoint
and compile errors, current/stale verification, read-only inspection/lock checks,
and 16 selector/policy/path/alias refusals passed.

Retained case:
`/private/tmp/kvik-k12-script-acceptance/.kvik/tests/scripts-cli/case-02v_7wp7`.
Phase `standalone-9d82sckm/summary.json` binds 404 evidence files, including exact
commands, configuration/lock snapshots, action specs, worker requests, observations,
CAS blobs, journals and failed-command receipts. Its SHA-256 is
`e80c8617dbb0a8e981b3e6966f01050f013f97d4cf2c9ed79c75d200ec336f3f`
(102,823 bytes). Outer summary `summary-1792698182337597000.json` is
`03424d427dff52b0b548675fae97a8ef6ff3f3019b66cf1be889f8010f7bf060`.
Earlier failed harness attempts remain separate and are not counted as this pass.

Independent standalone audit passed without re-executing consumers:
`/private/tmp/kvik-script-standalone-audit-448kr1cp/report.json`, SHA-256
`7a7b8a293b4b6fe78a74e9f2c0cb6b4e7f32ac35bc3cae8b39671b505d005f1d`.

## Completed named workspace-plugin gate

Handle `71246` exited 0 with the same frozen harness/runtime and retained case.
Phase `named-g3kmle_p` ran 17 CLI commands and retained eight complete run records.
Its summary binds 922 files, SHA-256
`6dabab71642f15782915dd7f535fd1dd272bbeeab62f6b9c560a14f43e47a014`
(284,704 bytes). Cold execution ran all eight actions; warm execution reused seven
compile actions while the script produced a fresh effect.

The real K13 workspace compiler plugin rewrote the script marker and emitted its
exact report. Full ordered compiler, plugin-host and application classpaths match
the selected lock closures; private module imports fail while required private
runtime dependencies remain available. The checks cover catalog/profile origins,
read-only scoped inspection and dependency queries, selected-input freshness,
resource and declared plugin-input edits, plugin-source recompilation, profile
compiler-option changes, and a real catalog dependency version change. All seven
successful script invocations produced effects; failed compilation did not.

## Completed owned-process lifecycle gate

Handle `65609` exited 0. Phase `lifecycle-14rhvoeo` retained resolve and warmup,
then four real Java application/child/grandchild scenarios with slow shutdown
hooks: declared timeout, SIGINT, SIGTERM, and abrupt CLI owner SIGKILL. Summary
SHA-256 `6cd14abe59142f3bf53974a571e9cee2396c61ab73dfca0efbbf09bb3ba36e49`
(38,244 bytes) binds 151 files and four complete run records. The abruptly killed
owner correctly has no fabricated completion record.

The captured ownership sets include 20 PIDs, four guardians and four application
process groups. Timeout returns 124; INT/TERM return 130. For these ordinary
terminal outcomes the first checked process table already contains no captured
live process or application-group member. The owner-loss case returns -9 and
its bounded asynchronous cleanup finished in 0.440 seconds. INT and TERM terminal
cleanup observations took 0.409 and 0.455 seconds; timeout observation includes
its configured three-second deadline. These are host observations, not benchmarks.
Separate emergency-cleanup files cannot satisfy the terminal assertions.

## Reproduction and remaining scope

For the historical three-phase gate, use frozen harness `c17882c` and the
schema-tools Python environment with `jsonschema` installed:

```sh
python -B tools/test_scripts_cli.py \
  --runtime /private/tmp/kvik-k12-script-cli/.kvik/script-cli-integrated/runtime/executables/d87e052a81fae1f63f0d5c4958edc4b2a5d2e1ebf1baa7c4c81fed1cd7909075/kvik.kexe \
  --tools /Users/yoda/dev/pet/kvik/.kvik/tests/cli-native/case-gki_vdpd/tools.json \
  --schema-root /private/tmp/kvik-k12-script-cli --phase standalone
```

The retained commands used `--phase standalone`, then `--case` with `--phase named`
and `--phase lifecycle`, creating separate immutable phase attempts. No runtime
rebuild was performed by this harness. This is not full K12 completion: published
serialization-plugin use, standalone external dependencies/offline behavior,
successful workspace plugin-option/helper invalidation, compiler identity changes,
and concurrent worktrees with shared-cache cancellation isolation remain separate
gates. Escaped process sessions, direct guardian SIGKILL and uninterruptible kernel
tasks are outside the owned-process guarantee.


Independent named and lifecycle audits passed without reruns:
`/private/tmp/kvik-script-named-audit-y6axyftg/report.json`, SHA-256
`80ec9926bf481d64db8d62ae0dfc86266fb07fcb31b3510a514ed629a1e80fa9`, and
`/private/tmp/kvik-script-lifecycle-audit-iulzbccc/report.json`, SHA-256
`17d899c2c371c8c463cd76e7da8b513256de1fdab2c65cbf851706f7443d837b`.
The latter independently confirms the owner-loss first table still held four live
owned descendants, followed by bounded cleanup; no ordinary terminal claim uses
that exception.

The source-only extension freeze added published serialization, successful
workspace plugin-option/helper invalidation, and two Git worktrees sharing a CAS.
They were pending at that freeze; the actual results follow below. The original phase
receipts remain bound to their original frozen harness and runtime.

Extension source freeze `a82b24e` passed 14 light checks (11 central harness and
three published-fixture checks). Retained source/Python/log hashes are in
`/private/tmp/kvik-k12-script-acceptance/.kvik/tests/scripts-cli/light-extensions-su2zvzzl/receipt.json`,
SHA-256 `7570c6b51a84fd708ac479e7135a3731c6fb072ad07f20b2b75e388ec8808e5f`.
These checks forbid process launches during preparation and challenge substituted
extension source and lost/replaced survivor processes. They do not prove actual
shared-worktree execution.

## Completed refreshed-runtime extension phases

Frozen harness `3adf54a` (including the reviewed peer module correction) ran the
following phases against runtime A composition
`2f5b5ccdd270b064b4829fb631b18187b1aff5cc300e771036e89c6894f70e53`.
Both this runtime and alternate B composition
`a42334fed9e958864b3f949c650d398bbe4ce388fce183c32edd13be0b3608a6`
were bound from the first invocation. B was not executed by these phases.
Case: `/private/tmp/kvik-k12-script-acceptance/.kvik/tests/scripts-cli/case-pics68_s`.

The first published attempt (`82650`, `published-h0om6fxj`) stopped at a harness
policy mismatch after four successful serializer executions: it expected offline
resolve to update the lock after a configuration change. Production correctly
returned `KVIK_LOCK_INVALID`, with the exact stale-configuration diagnostic. All
original commands, streams, partial successful runs and `failure.json` remain.
The reviewed correction explicitly proves offline refusal without writes, performs
an explicit resolve, then checks the resulting lock offline. The retry began a
fresh phase directory; it did not synthesize a continuation of the failed attempt.

Published retry `93917` exited 0. `published-74g94ekw/summary.json` is SHA-256
`5b40b907f792e43639850be1a772d527479addb5beb1cb4673d2f2c7da69d596`
(105,212 bytes), binding 414 files, 25 commands and eight run records. Five actual
fresh effects prove generated serializer execution. Exact pinned runtime/plugin
artifacts, complete classpaths and CAS bytes, offline warm reuse, source/helper
invalidation, missing-plugin compilation failure, specific options/version
refusals and restored cache reuse all passed. Published plugin options remain
unsupported and are explicitly tested as refusals. Independent read-only audit:
`/private/tmp/kvik-script-published-audit-xjzxs0tr/report.json`, SHA-256
`f14de7640ed7b383d75943be136c69a61f1a97e210b3b56a803d6e7be1bc56ff`.

Named invalidation `29626` exited 0. `named-invalidation-mbxgybgx/summary.json` is
SHA-256 `9ac4439d8d913f877352cd8664a88951849faca4c6a9f1c94bdc8f1b52c1a472`
(210,870 bytes), binding 689 files, ten commands and five fresh successful runs.
The workspace plugin accepts changed options and produces the exact changed
report while module compilations remain cache hits. A helper body change affects
helper/producer/script compilation. An independent ordinary library body change,
with exact unchanged configuration and dependency-lock bytes, changes library
output bytes plus library/script compilation keys; other module keys and hits
remain unchanged. Program output reflects each change. Final compilation reuses
all seven eligible compile records and still executes the script freshly.

Concurrency `86618` exited 0. `concurrency-hw6hta69/summary.json` is SHA-256
`c592dc584a8f18e109d75af648644daa89a55c640ceb481942a39f122760676c`
(59,256 bytes), binding 247 files including two child summaries, six run records
and all eight actual CLI invocation streams/commands. Fixture-only Git revision
`29514511edc6c2b39e947d8f7c59419344770c8c` was checked out into two detached worktrees.
They share a CAS but have separate configuration locks, run directories and effects.
The relocated warm compile uses the same key
`c15523fe9f1728956cf9285a3396f0dc3025d399a6c5442a47c76fd73edf10e8`.
Both live invocation trees were captured: ten PIDs including two guardians and
separate application process groups. After cancelling A, its first terminal table
is drained while B's exact captured tree remains alive. Only then is B released
through its own project-local file and allowed to complete normally. Both worktrees
subsequently execute freshly using cached compilation. This proves warm shared-CAS
cancellation isolation, not simultaneous cold cache publication contention.

The complete final extension source passed 18 light checks (12 central, three
published and three compiler-identity guards), receipt
`light-offline-policy-k0put5is/receipt.json`, SHA-256
`c51d5922783406cc1c45eb5a1ef0e30c55052ad22cf2be467c5a1afb6d423f55`.
The compiler-identity phase remains unexecuted: the A/B legacy bootstrap receipts
bind source, launcher invocation/events and artifacts but do not capture complete
compiler subprocess argv/environment. That required provenance remains pending;
the bounded source/order audit does not replace it or establish full K12 completion.
