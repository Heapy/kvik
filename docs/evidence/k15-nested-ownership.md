# K15 transitive Native process ownership

This slice fixes nested Kvik process guardians being killed by an outer worker's
process-group cleanup. It implements the per-level completion/poison protocol in
[the decision](../decisions/k15-nested-ownership.md), isolated guardian groups,
strict inherited capability validation, same-PID exec continuity, terminal
restorer retention, bounded Darwin group-quiescence checking, and explicit
Native poll/close protocol failures. The compiled C capability version is 1.
No core ProcessRequest schema, application action, or Session implementation is
changed here.

All focused gates below passed against the final production source. The new C
and Native harnesses bind source/tool/command/environment/binary identities and
reject stale no-build use. The existing Native lifetime harness does the same.
Compiler/JDK/SDK support outside these bounded receipts must remain stable during
compilation and across reuse; these receipts do not claim a complete installed
SDK inventory. The existing direct terminal harness is rebuilt from source and
has ordinary scenario logs, not the new immutable build-receipt interface.

| Gate | Retained case | Result |
| --- | --- | --- |
| C nested ownership | `.kvik/tests/nested-ownership/case-8jhkh7vh` | 24 cases, handle 91952 terminal 0 |
| Existing C terminal boundary | `.kvik/tests/foreground-keys/boundary-7rtpiaxd` | 19 cases, handle 66853 terminal 0 |
| Existing Native process lifetime | `.kvik/tests/process-lifetime/case-wqzjjuh3` | 10 groups, handle 73642 terminal 0 |
| Native failure/sibling cleanup | `.kvik/tests/nested-ownership-native/case-5sv7li_x` | Passed, handle 36048 terminal 0 |

The C summary SHA-256 is
`43223beb7dd0ceac11fcc3a0ea27c6e7b6310b94ad82f9541d41f28733b61fa4`;
its build receipt is
`40a2c5408d188bc012475f14e1029e77632e46018d6d43997abf1db627e80855`.
The Native lifetime summary is
`e4944ef62cc4c376fc6c195067d572b9dafb5c33a624f0dcca9abad4ba6cd6d3`;
its build receipt is
`b9128a37a3256b99198de656860d34b33f29517c5fbe536042ed604eaf8eaa05`.
The Native failure summary is
`43769666575e890e0650a28ffa827414a85b9da4b4c61b3686650f38871c044f`;
its build receipt is
`fc6df26663b0054e54f83fa304b5489bd3301a610d697a4eda5cca6801e161ab`.

The decisive C checks pause the deepest actual guardian after its resistant
application and child exist. Cancellation at two and three ownership levels
cannot reach terminal while that guardian is paused beyond the old 200 ms
termination grace. Darwin automatically resumes stopped orphan groups, so the
harness temporarily joins an inert harness-owned anchor to that guardian's group;
the anchor's parent remains outside the group. Removing the anchor and continuing
the guardian releases this explicit test gate. At outer terminal, checked `ps`
snapshots require every captured process and every member of its owned groups to
be absent or zombie; there is no eventual-cleanup allowance for terminal claims.
Ordinary leader exit, intermediate/root owner death, positive/zero deadlines and
independent invocation isolation are covered too.

Other checks cover successful/failed same-PID exec, reserved environment override,
malformed and oversized capability markers, wrong FD kind/access/identity,
blocking writer rejection, occupied descriptors 3–9 and no unrelated inherited
FDs in workers. A separately compiled test guardian substitutes a failing sysctl
function while retaining the actual cleanup implementation: its poison reaches
the root. Nested malformed result framing also reaches the root as C poll -1,
not an application exit. The actual terminal-restorer check holds the guardian
paused after its owner dies and requires the upstream FIFO to remain open;
empty EOF is accepted only after terminal restoration has completed.

The Native probe runs the production NativeProcessHost against both missing and
malformed result streams. Its shared host has a broken handle before a live
resistant sibling. Poll throws, host.close still cleans the sibling before
throwing, and repeated handle/host close does not retry freed pointers. The
terminal snapshot contains no live captured owner, guardian, worker or group
member. The existing lifetime gate preserves ordinary application exits and
cancellation ownership with positive and zero deadlines.

Exact reproduction from this worktree (the configured host is macOS ARM64):

```sh
python3 -B tools/test_nested_ownership.py
python3 -B tools/test_foreground_keys_boundary.py
python3 -B tools/test_process_lifetime.py
python3 -B tools/test_nested_ownership_native.py
```

The two Native tools default to the pinned
`~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20` and
`~/.sdkman/candidates/java/25.0.4-librca`. They use explicit compiler environments
and owned compiler invocations. New harness `--no-build` options revalidate
receipts; the terminal harness's older `--skip-build` is not used for this proof.

Retained failed development cases are not passing evidence: `case-hzc9kyhh`
exposed Darwin's automatic orphan-group CONT in the first pause fixture;
`case-neeo6hvz` and `case-sxi9m11a` each passed 23 process cases before test-only
terminal broker teardown/CONT races. The first Native sibling fixture
`case-22biqz0r` used nonexistent macOS `/bin/true`; retained diagnostic
`diagnostic-yjtd38mh` identified ENOENT. The final fixture uses `/usr/bin/true`.
Production source did not change for these fixture corrections.

These are Native-boundary and standalone terminal proofs. Mixed Python bootstrap
ownership, foreground application replacement and source-runtime handoff require
separate integration gates. Old Kvik boundaries must not be admitted for
recursive application ownership merely because their resume schema is accepted.
A direct hostile guardian SIGKILL, arbitrary process/session escape and an
uninterruptible kernel child remain explicit boundaries. A direct hostile
guardian kill can release its writer without executing the poison path; it is
not covered by the empty-EOF guarantee. Within the supported ownership protocol,
no timeout substitutes for acknowledgement and explicit verification/transport
errors are not normalized to an ordinary exit.

## Independent review and integration

Independent review approved frozen `f48694bfcd1472926eee0dc90efb2af907ad769c`.
The audit rehashed 47 committed source/support files, command and environment
receipts, tool inputs, binaries and logs for the C24, Native lifetime10 and
Native protocol/sibling gates. It checked the depth-two/depth-three paused
snapshots, poison outcomes and completion-time process groups. The 19 terminal
cases retain exact restoration logs; their older harness does not provide the
same immutable receipt as the new gates, so that evidence has a narrower binding.

The verdict is `/private/tmp/kvik-nested-receipt-review-2fu2rzvw/verdict.json`,
SHA-256 `b56a31db7f969d1eb724ea2dbdb4b83d0e7147bc9d56f09270101cbc095b3c15`.
The detailed audit is `report.json` in the same directory, SHA-256
`8bbab10a968cf8a263489a18392d6136c5c4d3ae0a5c70d2a5a5a3f1e5c179b6`.

The reviewer also reran the original Native leak reproduction using the matching
final lifetime binaries. The outer result arrived after 0.747 seconds with no
captured owned process/group live; final cleanup was verified. This is an actual
independent replay, not just a receipt audit. Its report is
`/private/tmp/kvik-nested-guardian-fixed-review-u3epmghp/report.json`, SHA-256
`781306fbf393f6c55aa4fd46404958b30b60356ba85c21ef8131ea5f11bd6de0`.

Main integration `d0630fd` matches all 15 files of the reviewed commit exactly.
The merge audit is `/private/tmp/kvik-nested-merged-audit-xt5w8lmz/report.json`,
SHA-256 `b8eda1d63c0203d818ee18c18f46e68a1fe6fcac2d2b9ee967eea059936d9bc2`.
