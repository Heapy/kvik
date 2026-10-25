# Explain a retained action

```sh
./kvik --offline --no-build -- explain \
  --run /absolute/project/.kvik/runs/RUN_ID --action app:jvm:main
```

The versioned JSON response contains the action's outcome and reason, action key,
declared inputs, prerequisite results, tools and settings. Its schema is
[`schemas/v1/explain.json`](../schemas/v1/explain.json). A valid explanation exits
zero even for a failed or blocked action. A missing action exits two; missing or
inconsistent retained records exit three.

`explain` reads the historical run without checking current sources, installed
tools or artifact availability. `currentFreshness` is null. Use
`verification --run PATH` to check whether a result still describes current inputs
and tools.

Reasons distinguish cache reuse, required fresh execution, within-run sharing,
failure, cancellation and blocking. New runs record the cache decision before
execution or reuse: policy bypass, local hit, miss, or lookup/entry-validation
error. `cacheDecision` includes that choice and any error detail; failure and
cancellation remain the primary outcome. Older runs may have no recorded cache
choice: execution alone does not explain a cache miss or prove sources changed.
Cache notices without an action ID remain labelled as run-level context.

`timing` exposes `startedAfterNanos` (relative to scheduler creation) and
`durationNanos`. The monotonic elapsed interval includes executor startup, cache
lookup, execution, polling and cleanup; it excludes waiting for prerequisites or
scheduler capacity and does not represent CPU time. A cache hit measures this
invocation's lookup and restoration, not the original compilation. The same data
is available in an `action-timing` event before `action-finished`.
Older runs, unstarted actions and actions sharing another action's result have
null timing. For shared work, inspect the action named by `reason.sharedWith`.
Timing is run-local and never changes action keys or cached results.

`cacheTransfers` lists remote lookup and publication operations, including their
outcomes and `stats`: request count, uploaded/downloaded HTTP body bytes and
operation duration in nanoseconds. Body bytes include gRPC framing but exclude
HTTP headers and TLS overhead. Duration includes local mapping and validation
within the remote operation. These records also appear as `action-cache-transfer`
events; publication can finish after the action itself. Publication failure does
not change a successful local action result. Interrupted or unmeasured operations
can have null stats, which must not be interpreted as zero traffic. Cancellation
does not roll back a remote write that the server already accepted. An empty list
means no recorded remote operation, including older runs and local-only reuse.

`dependencyEvidence: "unbound-retained"` means the dependency IDs come from the
saved action record; the retained dependency list is not part of the action key.
An unresolved action has null recipe and dependencies because these details were
not retained.

## Summarize a run

`kvik run-stats --run PATH` returns a read-only JSON summary using
[`schemas/v1/run-stats.json`](../schemas/v1/run-stats.json). It needs only the
committed verification and event journal, with no project, tool installation,
action recipes or artifact cache. A valid historical query exits zero even when
the recorded run failed; damaged or incomplete records exit three.

`actions.startedOutcomes` counts outcomes with an action-started event.
`unstartedOutcomes` keeps shared results, blocked actions and failures before
startup separate, so they do not inflate execution or cache-hit counts.
Requested/completed checks and test counts retain their original scope.

`timing.observedSchedulerNanos` ends at the latest measured action completion,
relative to scheduler creation. `observedBusyNanos` is the union of measured
action intervals, while `summedActionNanos` sums their durations and can exceed
elapsed time. `peakObservedActiveActions` counts overlapping measured intervals;
it describes scheduler activity, not CPU utilization or process count. These
fields exclude bootstrap and other work outside the measured intervals. The ten
slowest measured actions are listed in descending duration order.

`remoteCache` totals measured lookup/publication requests, body bytes and
operation durations. Operation durations can overlap and are not wall time.
Both timing and transfer sections label measurement coverage as complete,
partial or unavailable. Partial values cover only recorded measurements;
unavailable totals are null. An old journal with no transfer records does not
establish zero network traffic. This query does not check current freshness,
infer avoided tests, or estimate memory consumption.

`inputCapture` reports the initial successful input capture before action
scheduling: its elapsed nanoseconds and SHA-256 operation count, bytes and elapsed
hashing nanoseconds. Filesystem capture includes both observations; source
provider capture uses the selected immutable manifest. Repeated hashing of the
same bytes counts repeatedly. Hashing includes the snapshotter and its capture
CAS calls, including manifest/fingerprint hashing; JSON encoding, file IO,
configuration parsing and CAS publication are included only in capture elapsed
time. Provider-internal identity verification/fetching, initial configuration
preflight, later freshness scans, action keys, tool inventories and runtime
bootstrap hashes are outside the hashing counter. Thus this is not total process
hashing time. Capture occurs before the scheduler origin and is not added to
scheduler timing fields. Old journals return null; unsuccessful captures that
never created a run still return their original diagnostic without run statistics.
