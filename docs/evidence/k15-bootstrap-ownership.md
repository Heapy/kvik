# Python bootstrap recursive ownership

Frozen implementation `4552add32e77bb0a2a8993a735f69e754eca56da` carries the
version-1 Native completion capability through Python compiler and source
acquisition guardians and same-PID launcher exec. Each guardian waits for its
worker group and downstream acknowledgement before releasing upstream ownership.
Missing acknowledgements and cleanup failures poison the upstream capability.
The [decision](../decisions/k15-bootstrap-ownership.md) records the descriptor,
platform and cooperative-process boundaries.

## Final focused validation

Actual job `35308` terminated with exit 0 from
`/private/tmp/kvik-k15-bootstrap-ownership`, using the retained runner
`/private/tmp/kvik-bootstrap-final-gate.py`. Its SHA-256 is
`6c94c65716ce381c0423259acae8d1f3ea811775b54b954efab9219709ffa9ee`.
The runner executed these existing test files with
`/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B -m unittest discover -s tests/bootstrap -p FILE`:

| File | Passed tests |
| --- | ---: |
| `test_ownership.py` | 5 |
| `test_source_process.py` | 11 |
| `test_process_barrier.py` | 7 |
| `test_launch_descriptor.py` | 5 |

All **28 tests passed**. They cover existing ownership behavior, acquisition
deadlines, capability validation, descriptor collisions/leaks, exec preservation,
failed-exec restoration and launch descriptor compatibility. Helpers disable
bytecode writes; the final gate found no generated bootstrap bytecode.

The launch descriptor tests explicitly overlaid
`43403df:schemas/v1/bootstrap-launch.json` (SHA-256
`4662bb74a4c09f3bbc84c2185148603c06e5fdd697e0b3425ed2556bb96114ca`),
then restored the baseline schema in a finally block. That foreground schema is
a required integration dependency, not part of the Python source freeze.

Retained summary:
`/private/tmp/kvik-k15-bootstrap-ownership/.kvik/tests/bootstrap-ownership/case-z5re2s_b/summary.json`,
7,652 bytes, SHA-256
`5ab9566c8f0b22b2cfc430f09a017538c9836e6c7d1c6d5cdec10a9fa7c69af6`.
It binds 23 committed source/test/harness files, Python and runner identities,
commands, durations, stdout/stderr digests, the schema dependency and restoration.

## Mixed Native/Python ownership

The same successful job ran:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_bootstrap_nested_ownership.py \
  --native-case /private/tmp/kvik-k15-nested-process-ownership/.kvik/tests/process-lifetime/case-wqzjjuh3
```

This uses the retained, receipt-bound Native probe and guardian; it does not
recompile them. All **four scenarios passed**, covering compiler and acquisition
Python guardians in both delayed-cleanup and protocol-failure paths.

Each delayed case captures a real seven-process ancestry chain through Native,
Python and Native owners/guardians plus a child. After stopping the Python
guardian and cancelling the outer Native owner, the outer owner remains pending
past the old cleanup grace interval. After resuming the guardian, every captured
owned PID/group is gone before the successful completion observation. Each
failure case makes an actual inner Native helper omit its acknowledgement and
requires failure propagation through Python to the outer Native owner, without
a false successful result. Failure cases start no application and do not claim
an application-group lifecycle.

Mixed summary:
`/private/tmp/kvik-k15-bootstrap-ownership/.kvik/tests/bootstrap-ownership-mixed/case-rsbdmwkj/summary.json`,
7,013 bytes, SHA-256
`5076c62829f11525b634f988515a12055fd7d493dd02c84b1519198e934269d1`.
Commands, captured process inventories and raw logs remain beside it.

## Independent review and limits

An independent read-only source and retained-evidence review approved `4552add`
without a confirmed defect. It checked the final 28-test receipt, matching mixed
cases, source/tool/binary hashes, descriptor schema overlay/restoration and
captured cleanup observations; it did not rerun compilers or processes.

- `/private/tmp/kvik-bootstrap-ownership-review-qbuyu989/report.json`, SHA-256
  `dd347ba03d5181a9d1c0d6a0328a85505bd2518bd25ccc1560996aa789c1bfe9`.
- `/private/tmp/kvik-bootstrap-ownership-review-qbuyu989/verdict.json`, SHA-256
  `3a3dc0fa5f046438a51ae26c579e5107e37fa945e85b7e2f85cc14006cb90a40`.

This establishes the focused bridge behavior on the pinned Darwin host, not a
portable process-inventory implementation. The ambient harness environment is
not a fully pinned compiler environment. Full public JVM/Native foreground
replacement and actual compiled-source handoff remain separate acceptance gates.

## Main integration

Foreground contracts/controller and this bridge were integrated together through
`e835420`. A read-only merge audit compared all 38 changed files byte-for-byte
against `cd7f522` and `b33ad73`; all matched. Three strict foreground/bootstrap
schema tests passed against the combined main tree. The user's unstaged PLAN.md
change was excluded from integration and its working diff recorded separately.

Receipt: `/private/tmp/kvik-foreground-bootstrap-merged-audit-81_p2d3q/report.json`,
8,785 bytes, SHA-256
`76ca8bee2882d71d693e1e092563f46f457dbff3fd2ba10c16bda0a158bc0a4b`.
This is source/schema integration evidence; it does not replace the pending full
composed runtime build and public foreground acceptance.
