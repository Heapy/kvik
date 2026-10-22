# K12 public script CLI integration

Named project scripts and standalone `.kt` files connect to dependency locks,
snapshots, the compilation graph, local compilation reuse and fresh owned JVM
execution. The six K12 acceptance gates passed on the composed native executable
within the supported JVM script contract. The broader PLAN remains incomplete.

The commands are:

```sh
kvik resolve-script maintenance --project /project/kvik.json
kvik check-script-lock maintenance --project /project/kvik.json
kvik inspect-script maintenance --project /project/kvik.json --tools /project/tools.json
kvik run-script maintenance --project /project/kvik.json --tools /project/tools.json -- ARGUMENTS
```

For a standalone file, replace `maintenance --project /project/kvik.json` with
`--file /project/maintenance.kt`. The file requires `maintenance.kvik.json` alongside it;
its default lock is `maintenance.kvik.lock.json`. An explicit `--lock` overrides that
default. The companion must declare the selected file among its source inputs. Sources
can include helper files/directories; scripts use Kotlin/JVM and ordinary compiled main
methods. Main discovery examines only the script's own class metadata. Multiple mains
require an explicit `mainClass`.

Resolution is explicit. Running and inspecting consume an existing lock and verified
cached dependency bytes. Inspection also verifies the installed tool identities and
current source snapshot, emits versioned JSON, and performs no compilation or execution.
`dependencies --script NAME --project PATH --json` filters the project dependency query
by typed script consumer. A same-named module remains a distinct consumer.

Pass program arguments after `--`; empty strings, spaces and leading `@` remain literal.
Execution uses the project or companion directory, declared environment, EOF stdin and
private temporary/home directories. The pinned Kotlin standard library is supplied to
compilation and execution after explicitly declared dependencies. Compilation can be
reused; the script action always executes. CLI stdout contains JSON events; retained
script observations identify separate `script.stdout` and `script.stderr` files. Scripts
can perform effects in their working directory; this ownership model is not a sandbox.

## Retained checks

Candidate `f80a6f3` compiled to a Kotlin/Native CLI in 110.3 seconds with unchanged tracked
sources. Runtime composition is
`34a84a6c9f12681f94e8b462d627c1a54ae86605bf2d15b0885106882cfe2243`;
the executable SHA-256 is
`341125acb2848fa333aa481842c70ae02a33faa5ccf56bc039eeb9b95b62a663`.
Source/build receipts and launch descriptor are under
`/private/tmp/kvik-k12-script-cli/.kvik/script-cli/`.

The first public standalone smoke case is
`/private/tmp/kvik-k12-script-cli/.kvik/tests/script-cli-smoke/case-6vqfxqkx`.
Resolve, check-lock, inspection and both cold/warm runs returned zero. The first run
compiled and executed; the second reused compilation and executed again, with two
retained effects. Inspection and emitted event documents passed their schemas.
However, its subsequent exact-stream audit failed: Java's standard output used ASCII
under the fixed C locale, replacing `☃` with `?`. This is a failed Unicode acceptance
case despite successful command exits. `stream-audit.json` retains that distinction.

Two probes reused the identical compiled fixture and showed that argv retained Unicode:
explicit Java stdout/stderr UTF-8 properties restored the exact bytes, as did a UTF-8
locale. Commit `8de6d41` sets the stream properties while preserving the controlled
environment. Its regression fixture checks both BMP and supplementary Unicode through
the actual stdout and stderr streams. The separate Native executor gate passed all 19
groups and ten real Java invocations at
`/private/tmp/kvik-k12-script-cli/.kvik/tests/script-executor/case-9ml_dj6t`.
The rebuilt-runtime replay and subsequent public phases are recorded below.


After composing the reviewed foreground recovery and structured process-result changes,
`af9b37f` passed 20 Native executor groups and ten actual Java invocations in
`case-trewcpd6` (receipt `03718e72b5dd02aa2758cf19d8e49ea2ff307fdc11b7c3b3c488084b9e694b0c`).
Script observations retain nullable `processResult.setupFailure` with bounded stage/errno.
Cancellation and timeout retain their status priority. Otherwise a host setup failure
returns CLI exit 1 with `KVIK_SCRIPT_LAUNCH_FAILED`, while an ordinary script choosing
125 or 127 returns that exact value with no setup failure. Source review found no issue;
the new Native group verifies setup retention and cleanup, and the earlier exact exit
cases remain passing. Full public failure-path evidence remains a separate gate.

The combined runtime at `26661c2` subsequently built successfully with all tracked
inputs unchanged (105.2 seconds wall time). Its composition is
`d87e052a81fae1f63f0d5c4958edc4b2a5d2e1ebf1baa7c4c81fed1cd7909075`;
executable SHA-256 is `db7519af140d30460f8e380ec316d15d912741976e832096fe873145565e9ef6`
(5,585,096 bytes). The independent audit bound the historical source receipt, composition
sources, build recipe, executable, guardian, worker classes and compiler artifacts:
`/private/tmp/kvik-foreground32-runtime-audit-pm_f5nsz/report.json`, SHA-256
`d0646f40a16f4be7c4e030594f4787e1c8902a8844fe68a080f68de6c36b7595`.
It does not independently re-inventory every installed SDK/JDK support file.

A real cold script on that runtime returned zero, with exact NUL/BMP/supplementary
Unicode stdout/stderr, literal newline/empty/space/leading-`@` arguments, declared
environment, EOF stdin and the expected working directory. The first acceptance attempt
stopped during its receipt audit because the Python harness used JSON short control
escapes, whereas `kvik-json-v1` uses `\uXXXX` and UTF-16 key ordering. This was a harness
failure, retained at
`/private/tmp/kvik-k12-script-acceptance/.kvik/tests/scripts-cli/case-iwml830f/standalone-bi6pps24`.
Correction `c17882c` preserves the separate bootstrap encoder; an independent encoder
then re-audited the same retained completion, journal, action keys, CAS and exact streams.
Report: `/private/tmp/kvik-script-core-canonical-review-i3wgzalb/report.json`, SHA-256
`cc81265be2c9ee5400f94c25249c2edd7f53d2e4a00c1e26a4caa0542fb40527`.
That single retained cold run was followed by the complete standalone phase in
`case-02v_7wp7/standalone-9d82sckm`: 35 commands, 11 runs, 73 events and seven fresh
runtime effects. Cold and warm invocations shared the compilation key, changing from
executed to local hit, while each script invocation ran again. Exact Unicode/control
streams and literal arguments passed; ordinary exits 125/127/130/255 remained ordinary
exits, separate from timeout, cancellation and host setup failure. Inspection, lock
checks, freshness queries, main discovery, compilation failure and 16 preflight
refusals also passed. Summary SHA-256:
`e80c8617dbb0a8e981b3e6966f01050f013f97d4cf2c9ed79c75d200ec336f3f`.
The independent retained-evidence audit approved this bounded phase:
`/private/tmp/kvik-script-standalone-audit-448kr1cp/report.json`, SHA-256
`7a7b8a293b4b6fe78a74e9f2c0cb6b4e7f32ac35bc3cae8b39671b505d005f1d`.

The same case/runtime subsequently passed the named workspace-plugin phase
(`named-g3kmle_p`, summary SHA-256
`6dabab71642f15782915dd7f535fd1dd272bbeeab62f6b9c560a14f43e47a014`)
and lifecycle phase (`lifecycle-14rhvoeo`, summary SHA-256
`6cd14abe59142f3bf53974a571e9cee2396c61ab73dfca0efbbf09bb3ba36e49`).
The independent named-phase audit approved the retained evidence:
`/private/tmp/kvik-script-named-audit-y6axyftg/report.json`, SHA-256
`80ec9926bf481d64db8d62ae0dfc86266fb07fcb31b3510a514ed629a1e80fa9`.
The independent lifecycle audit also approved its retained evidence:
`/private/tmp/kvik-script-lifecycle-audit-iulzbccc/report.json`, SHA-256
`17d899c2c371c8c463cd76e7da8b513256de1fdab2c65cbf851706f7443d837b`.
For timeout/INT/TERM, absence was observed in the first checked postterminal process
table; owner SIGKILL requires separately observed eventual cleanup and has no
completion acknowledgement. These sampled observations do not claim zero-time
absence before the process-table query.
The named phase exercised catalog/profile dependencies, prerequisite reuse, ordered
compiler/plugin/runtime classpaths, resource/plugin input/plugin implementation/profile/
catalog invalidation and rejection of a private transitive import. Lifecycle covered
timeout, SIGINT, SIGTERM and owner SIGKILL with captured descendants. See the separate
acceptance evidence for exact commands and observations.

The standalone phase above has no external dependency; the workspace plugin is not
the published serialization compiler plugin. Subsequent extension results below cover
those additional cases. Compiler-identity execution subsequently passed as described below. Full installed tool inventories and historical filesystem no-write
assertions have the limits described in the audits.

## Published-plugin diagnostic follow-up

Commit `3bf1b8e` preserves the supported published-plugin contract while reporting
distinct errors for an unsupported coordinate, a compiler-version mismatch, options
and file inputs. Each message identifies the supplied plugin and the supported
coordinate or a corrective action. The graph tests cover all four failures and a
supported plugin. A separate test holds configuration, sources and composition fixed,
changes only `jvm-compiler`, and proves the action keys change while restoring that
tool identity makes the complete action specifications equal.

The Native graph/contracts/evidence gate passed 108 tests in 15 cases (550 ms test
execution), retained at `.kvik/tests/project-graph/case-7k0c90f1`. Summary SHA-256:
`d0d444606284000e94edcfcf6ff41f2c0b91f2a97d05fb0aae7ecfdf3ed0512c`.
Independent source/build/log audit:
`/private/tmp/kvik-script-graph108-audit-aojzmbf9/report.json`, SHA-256
`d98fa9d975b4d24192911594c1e4f016219abc9c0886215862816fa3ca019b32`.
This is graph evidence; actual alternate-compiler execution remains separate.

The native runtime rebuilt from `3bf1b8e` with all tracked source bytes unchanged,
in 106.164 seconds wall time. Receipts are under `.kvik/script-cli-diagnostics/`.
Composition:
`2f5b5ccdd270b064b4829fb631b18187b1aff5cc300e771036e89c6894f70e53`.
Executable SHA-256:
`ccc0860084ed3cecf12491e49fa5226fcdef5cea19e6aeab50d79667ba24311d`
(5,585,480 bytes). The guardian digest is unchanged from the prior runtime. This
build establishes the executable for the next published-plugin acceptance phase;
it does not itself prove consumer compilation or execution.

## Reviewed extension acceptance

Runtime `2f5b5c…` subsequently passed published serialization, independent invalidation
and concurrent-worktree phases in
`/private/tmp/kvik-k12-script-acceptance/.kvik/tests/scripts-cli/case-pics68_s`.
The frozen acceptance source was `3adf54a`; detailed commands, summary digests and
scope are in [the acceptance evidence](k12-script-acceptance.md).

- Published serialization: direct generated `Payload.serializer()` use, exact plugin/
  runtime classpaths and locked artifact bytes, offline compile reuse, source/helper
  invalidation, plugin removal failure, specific unsupported-options/version diagnostics,
  restored lock/cache reuse and five fresh UUID effects. Independent audit:
  `/private/tmp/kvik-script-published-audit-xjzxs0tr/report.json`, SHA-256
  `f14de7640ed7b383d75943be136c69a61f1a97e210b3b56a803d6e7be1bc56ff`.
- Named invalidation: changing plugin options, helper implementation and ordinary
  library implementation invalidated the expected compilations. The library change
  preserved configuration and lock bytes and left unrelated module actions as hits.
  Independent audit: `/private/tmp/kvik-script-named-invalidation-audit-rywh6p83/report.json`,
  SHA-256 `4498a58a8f9c2ae40c0d65b08d0cc8e1c3830c3ce5e33e6cbcb0acbdd2fdc7e0`.
- Concurrency: two real Git worktrees shared compilation CAS but retained distinct
  run roots and effects. Both process trees were observed live together; cancelling
  one drained it before the surviving tree was released to finish normally. Both
  later reran with cached compilation and fresh effects. Independent audit:
  `/private/tmp/kvik-script-concurrency-audit-kls3w19a/report.json`, SHA-256
  `feb75c78300bde8f20244206ed40bd62a31de063ce9779099351f02b86f154ae`.
  This covers warm shared-cache isolation, not concurrent cold publication.

The first published attempt remains a failed harness case: it incorrectly expected
`resolve-script --offline` to replace a stale lock. The corrected attempt asserts
offline refusal without writes, explicitly resolves the changed configuration and
then checks/runs offline. It does not reinterpret that failure as a pass.

The final compiler-identity phase passed on newly traced A/B runtime builds:
A cold/warm, B cold/warm, then A reuse produced two compilations, three cache hits
and five fresh script executions. The independent audit found no issues:
`/private/tmp/kvik-script-compiler-identity-actual-audit-u1c7kc53/report.json`.
The change swaps compiler-distribution classpath ordering; it does not test another
compiler version. The actual runtime composition also changes, while the graph test
separately isolates compiler identity with composition held fixed. Exact build and
execution details are in [the acceptance evidence](k12-script-acceptance.md).

The K12 acceptance gates map to the completed phases as follows:

| PLAN requirement | Passing checks |
| --- | --- |
| Locked external dependencies and offline standalone execution | Published serialization phase |
| Catalog/profile and workspace prerequisites, reuse and unrelated actions | Named and named-invalidation phases |
| Serialization compiler plugin, runtime separation and incompatibility diagnostics | Published serialization phase |
| Compilation reuse, fresh effects and argument forwarding | Standalone phase and repeated executions in all phases |
| Independent input invalidation | Source/helper and library body changes; plugin implementation, options and input changes; compiler-identity phase and isolated graph test |
| Failures, timeout, cancellation and concurrent worktrees | Standalone, lifecycle and concurrency phases |

Concurrency covers warm shared-cache isolation. Concurrent cold publication,
cross-machine reuse and other PLAN milestones retain their separate gates.
