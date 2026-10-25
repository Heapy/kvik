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

`dependencyEvidence: "unbound-retained"` means the dependency IDs come from the
saved action record; the retained dependency list is not part of the action key.
An unresolved action has null recipe and dependencies because these details were
not retained.
