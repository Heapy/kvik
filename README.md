# Kvik

An experimental Kotlin Multiplatform build tool, distributed with its build plugins as sources and compiled into a cached **Kotlin/Native** executable. Invocations own their workers; repeated development work uses an explicit foreground session.

The launcher compiles Kvik and its local source plugin into one Kotlin/Native executable. Its current commands include `help`, `runtime-info`, `inspect`, `resolve`, `check-lock`, `build`, `test`, `verify`, `run-script`, `inspect-script`, `resolve-script`, `check-script-lock`, `prepare-application`, `run-application`, `run-prepared`, `dev`, `verification`, `test-report`, `test-aggregate`, `coverage-report`, `coverage-check`, `coverage-aggregate`, `coverage-export`, `explain`, `packages versions`, `packages dependencies` and `dependencies`. Consumer commands connect the locked project graph to the JVM/Native adapters, local compilation cache, [remote JVM compilation cache](docs/remote-cache.md) and retained run results. [Foreground sessions](docs/foreground.md) retain project models and action graphs across reloads. See [PLAN.md](PLAN.md), [current status](docs/STATUS.md), [build command contract and limits](docs/evidence/k09-build-cli.md), [launcher evidence](docs/evidence/k14.md) and [v1 contracts](docs/decisions/0002-contracts.md).

With tools matching the explicitly provisioned host lock:

```sh
./kvik --java-home "$JDK" --konan-home "$KONAN" -- runtime-info
./kvik --offline --no-build -- help
./kvik --offline --no-build -- inspect --project /absolute/project/kvik.json
./kvik --no-build -- resolve --project /absolute/project/kvik.json
./kvik --offline --no-build -- check-lock --project /absolute/project/kvik.json
./kvik --offline --no-build -- test --project /absolute/project/kvik.json \
  --tools /absolute/project/tools.json --module library --target macosArm64
./kvik --offline --no-build -- verification --run /absolute/project/.kvik/runs/RUN_ID
```

`resolve` explicitly publishes `kvik.lock.json` and fills `.kvik/cache` relative to the project. `--store` selects a shared content store, `--lock` selects a lockfile, and repeated `--pin group:artifact:version` options declare resolver policy. Supply the same pins to inspection and lock checks. `check-lock` verifies the lock and cached artifacts without downloading or repairing anything. `resolve --offline` also performs only that check; the launcher's `--offline` propagates to dependency commands. Publication uses a persistent `.kvik-dependency-publication.lock` in the destination directory and rejects configuration/lock changes observed during resolution. See [CLI dependency evidence](docs/evidence/k09-dependency-cli.md).

### Configuration changes

`config validate` parses the configuration and validates the project graph and
resolver policy. It does not check a dependency lock or contact repositories.
`config plan` applies a batch of structured edits in memory, validates the resulting
project, and resolves its dependency lock. It returns the proposed configuration
and lock contents with digests of the original files; the original files stay
unchanged. Dependency resolution may populate the content store.

```sh
./kvik --no-build -- config validate --project /absolute/project/kvik.json
./kvik --no-build -- config plan --project /absolute/project/kvik.json \
  --edits edits.json > configuration-plan.json
# Inspect configuration.content and dependencyLock.content before applying.
./kvik --no-build -- config apply --plan configuration-plan.json
```

An edit batch has `schemaVersion: 1` and one to 128 `operations`, for example:

```json
{
  "schemaVersion": 1,
  "operations": [
    {"op": "set-dependency-version", "module": "app", "sourceSet": "main",
     "package": "org.jetbrains.kotlinx:kotlinx-coroutines-core", "version": "1.10.2"},
    {"op": "set-target", "module": "app",
     "target": {"name": "jvm", "main": "main", "test": "test", "compilerOptions": ["-Werror"]}}
  ]
}
```

The operations are:

- `set-dependency`: supply `module`, `sourceSet`, and a complete `dependency`
  object from the project schema. Adds or replaces by package or workspace-module
  identity, rejecting ambiguous duplicates.
- `set-dependency-version`: supply `module`, `sourceSet`, `package` (`group:artifact`),
  and `version`. Updates one existing external dependency, preserving scope and export settings.
- `remove-dependency`: supply `module`, `sourceSet`, and `selector`, either
  `{"package":"group:artifact"}` or `{"module":"library"}`. Requires one match.
- `set-target`: supply `module` and a complete `target` object. Adds or replaces
  by target name.
- `remove-target`: supply `module` and `target` (the target name). Requires an existing target.

The entire batch must produce a valid project; coordinated edits can temporarily
invalidate the graph between operations. Unrelated authoring fields are preserved.
Changed JSON is canonicalized, so formatting may change. The version in the example
is illustrative; `packages versions` lists repository versions before choosing one.
The edit and result schemas are in [contracts.json](schemas/v1/contracts.json), under
`configurationEdits`, `configurationValidation`, `configurationEditPlan`,
`configurationEditResult`, and `configurationSourceEditPlan`.

Planning accepts the usual `--store`, `--lock`, and resolver policy options. A local
lock must be inside the configuration directory, separate from source inputs and
the content store. `--offline` requires an existing lock compatible with the edited
configuration; it cannot resolve new dependencies. Applying a plan uses its recorded
paths and policy, verifies cached dependencies without network access, and rejects
changed configuration or lock bytes with `KVIK_EDIT_CONFLICT` (exit 3).

Local application serializes cooperating Kvik publishers and retains an intent in
`.kvik/configuration-edits`. The two files are replaced individually, so readers
can briefly see a mismatched pair; normal lock validation rejects that pair.
After interruption, repeat `config apply` with the same plan to finish publication.
A repeated completed application returns `unchanged`. If another editor changes
either file during recovery, application refuses to overwrite it; inspect the files
and plan again. External editors do not take Kvik's locks, so they are checked
before each replacement but are not excluded for the entire operation. There is
no automatic rollback over another editor's changes.

`config validate` and `config plan` also accept the revision source flags
`--kode`, `--kode-home`, `--source-project`, and `--revision`. A revision-backed
plan returns a Kode transaction with head and blob preconditions, using the same
explicit `kode tx` workflow described below for revision-backed `resolve`.
It publishes configuration and lock together in one revision. Local `config apply`
accepts local file plans only. An offline revision plan checks lock compatibility
and does not propose a transaction.

Package queries return versioned JSON for agents:

```sh
./kvik --no-build -- packages versions org.jetbrains.kotlin:kotlin-stdlib \
  --project /absolute/project/kvik.json --json
./kvik --no-build -- packages dependencies org.junit.jupiter:junit-jupiter-api:5.13.4 \
  --project /absolute/project/kvik.json --target jvm --usage java-runtime --json
./kvik --offline --no-build -- dependencies --project /absolute/project/kvik.json --json
```

`packages versions` supports `--channel all|stable|prerelease`, bounded pages and
cursors. Repository queries support `--refresh` and `--offline`, report their
sources and freshness, and use a separate query cache. `dependencies` verifies
the existing project lock and cached bytes without writes or network access;
supply the same pins and store used for resolution. Queries keep available
versions, original requirements and locked selections distinct. They do not
execute package code or change configuration/locks, and availability does not
establish compatibility. See the [query contract](integration/package-queries/CONTRACT.md)
and [composed CLI evidence](docs/evidence/k16-package-queries.md).

`workspace dependencies` connects locked dependencies to projects registered in Kode:

```sh
./kvik --no-build -- workspace dependencies \
  --kode /absolute/bin/kode --kode-home /absolute/kode-store \
  --store /absolute/dependency-cache --json
```

It discovers registered projects, pins their observed `main` revisions, and reads
`kvik.json` and `kvik.lock.json` from each snapshot. Dependency metadata and artifacts
must already exist in the selected cache; the query replays each lock without
downloads, builds or writes. Registry discovery is live, so the collection of
project revisions is not an atomic workspace snapshot. The output records every
successfully read project's source revision and configuration/lock digests.

Repeat `--source-project NAME` to restrict the graph, or `--at NAME=REVISION` to pin
a project explicitly. `--revision SELECTOR` applies a common selector to all
selected projects. `--project PATH` and `--lock PATH` set common logical paths;
`--configuration NAME=PATH` and `--dependency-lock NAME=PATH` override them per
project. Configuration paths are relative to a source revision, and lock paths
are relative to that configuration's directory. Supply resolution overrides as
`--project-pin NAME=GROUP:ARTIFACT:VERSION`,
`--project-scope-pin NAME=MODULE:TARGET:COMPILATION:USAGE:PURPOSE=GROUP:ARTIFACT:VERSION`
or `--project-exclude-metadata-edge NAME=JSON`; ordinary pin/correction flags apply
to every selected project.

Edges preserve module/script, target, compilation, usage and purpose from the
consumer lock, including transitive and redirected packages. Matching uses the
coordinates emitted by library packaging: target packages and multi-target roots.
`match` distinguishes exact ownership, multiple publishers, another available
version and packages outside the selected projects. Coordinate ownership leaves
`sourceArtifactEquivalence` unchecked: current source may differ from published
bytes. Missing inputs, invalid locks or ambiguous ownership return an incomplete
graph and exit status 3. The command supports up to 256 projects, 16,384 publication
coordinates, 65,536 edges and 16 MiB each of retained graph inputs and response JSON.
Detailed per-project paths and artifact lists are discarded after lock replay.
Discovery requires access to Kode;
there is no offline registry fallback. The output follows the
[workspace dependency schema](schemas/v1/workspace-dependencies.json).

Named project scripts and standalone `.kt` files use explicit dependency locks:

```sh
./kvik --no-build -- resolve-script maintenance --project /absolute/project/kvik.json
./kvik --offline --no-build -- run-script maintenance --project /absolute/project/kvik.json \
  --tools /absolute/project/tools.json -- "space argument" @literal.args
./kvik --no-build -- resolve-script --file /absolute/scripts/update.kt
./kvik --offline --no-build -- inspect-script --file /absolute/scripts/update.kt \
  --tools /absolute/scripts/tools.json
```

A standalone `update.kt` requires an adjacent `update.kvik.json` companion and defaults
to `update.kvik.lock.json`. Use `check-script-lock` with the same selector to verify an
existing lock. Compilation supplies the pinned standard library and can be reused;
script execution always runs afresh. Pass program arguments after `--`. Run stdout
contains JSON events, while observations identify the script's separate UTF-8 stdout
and stderr files. `inspect-script` reports inputs, expanded dependency/profile origins
and action prerequisites without compiling or running. See the [script contract](docs/decisions/k12-scripts.md)
and [CLI validation status](docs/evidence/k12-script-cli.md).

`build` and `test` always consume the existing lock and cached dependencies without network resolution. Select modules and targets explicitly with repeatable flags. JVM test targets declare `testSettings.classes` and optionally `testSettings.engines` (`junit` by default). Tests execute on every invocation; successful compilation can be reused locally. `--tools` names the explicit installed-tool configuration described in the build command contract. Run stdout contains versioned JSON events; compiler/test streams are retained in the run directory. `verification` checks whether a retained result still describes current inputs and tools. [`explain --run PATH --action ID`](docs/action-explanations.md) describes a retained action’s inputs, settings and recorded execution or reuse reason.

Use `verify` to select affected module/target checks since retained Kvik runs.
Checks default to `test`; repeat `--check build|test|abi-check` to choose the scope:

```sh
kvik verify --since /project/.kvik/runs/BASELINE_RUN \
  --project /project/kvik.json --module library --module app --target jvm \
  --tools /project/tools.json --plan-only
kvik verify --since /project/.kvik/runs/BASELINE_RUN \
  --project /project/kvik.json --module library --module app --target jvm \
  --tools /project/tools.json
```

`--since` takes a completed Kvik run directory. Changes are compared by content
and executable bits across the declared input inventories, including new,
deleted and renamed files. Git staging and commit state do not affect selection.
Production changes propagate through module dependencies and workspace compiler
plugins; test-only changes affect their own consumers. Configuration, dependency
lock, runtime or desired tool changes widen to all requested checks. Unknown input
ownership, damaged or older baselines without bound manifests also widen the
selection. A check missing from the baseline's completed checks always runs.

Repeat `--since` to combine completed checks from runs bound to the same input
snapshot, input selection, configuration, lock, runtime and compatible tools.
Runs from different snapshots force all requested checks. Mixed verification
runs capture ABI baselines for every group, so their execution runs can be
combined on the next invocation:

```sh
kvik verify --full --check build --check test --check abi-check \
  --project /project/kvik.json --module library --target jvm --tools /project/tools.json
kvik verify --since BUILD_RUN --since TEST_RUN --since ABI_RUN \
  --check build --check test --check abi-check \
  --project /project/kvik.json --module library --target jvm --tools /project/tools.json
```

An ABI baseline edit selects its own ABI check. Test source edits do not select
build or ABI checks unless that source is also a declared production input.
Build and ABI scopes can select modules without tests; ABI checks require
configured baseline files.

`--full` runs every requested check and can be used without a baseline.
`--plan-only` captures inputs and prints the selection without starting
compilers or tests. Ordinary `test` still runs every requested test; `verify`
executes every selected test freshly, using normal compilation cache behavior.
Its scope includes compilation and generator prerequisites. Build and ABI
checks may reuse verified cache entries. Documentation and script checks retain
their separate commands.

The JSON stream starts with `kind: "verification-selection"`, follows with normal
run events when executing, and ends with `kind: "change-verification"`.
Planning alone prints only the selection. Results distinguish `selectedChecks`,
`omittedChecks` and successfully completed `freshChecks`, with references to the
execution runs. For build and ABI, `freshChecks` means newly completed checks,
including valid cache hits. `baselineRuns` lists all supplied baselines;
`baselineRun` retains the first one for compatibility. `unchanged` means no checks
were selected; it does not describe a
fresh test run. Sources and desired tools are rechecked before and after
execution; changes during verification produce an incomplete result.

Selection and result records are retained under `.kvik/verification/` in the
project. Execution records stay in the usual run directory. These records
describe the selected scope and observed inputs; they do not establish that
undeclared external services or test fixtures are unchanged.

Use the [coverage commands](docs/coverage.md) to capture JVM line coverage, generate retained reports, check thresholds, aggregate compatible test checks and export SonarQube coverage XML or unchanged JaCoCo reports.

`inspect`, `build` and `test` can read an immutable project revision from Kode:

```sh
./kvik --no-build -- test --kode /absolute/bin/kode --kode-home /absolute/kode-store \
  --source-project library --revision main --project sub/kvik.json \
  --lock locks/dependencies.json --store /absolute/cache --runs /absolute/runs \
  --tools /absolute/tools.json --module library --target jvm
```

The reference resolves once; later edits to `main` do not change the run's inputs.
`--project` is relative to the revision root, and `--lock` is relative to the
configuration directory. Relative tool, cache and run paths use the invocation
directory. Build/test do not write source revisions. Equivalent local and stored
inputs share eligible compilation results; tests still run on each request.

Results and diagnostics include the immutable revision and project root. Use a
full revision ID with `--offline` to rebuild from cached source content, locked
dependencies and installed tools. Missing or corrupt source content causes a
failure. `verification --run PATH` checks the recorded revision using cached
source content without starting Kode; unavailable content yields `indeterminate`.

Online builds and tests acquire a Kode retention pin before fetching source
inputs. The pin protects the selected revision and its reachable source objects
through collection, and remains after completion so the recorded result can
refer to those sources. `run.json` and the `run-started` event contain its
`sourceRetention` identity. These operations require Kode retention support and
permission to write retention metadata. Offline builds use only cached inputs;
inspection, views and verification queries do not acquire pins.

Pins have no automatic expiry. A failed or interrupted build can leave a pin,
including failures before a run directory exists; find it with `retention list`.
Release a pin explicitly when its inputs no longer need provider-side retention:

```sh
/absolute/bin/kode --home /absolute/kode-store retention list library
/absolute/bin/kode --home /absolute/kode-store retention release library PIN_NAME \
  --expect-record IMMUTABLE_RETENTION_RECORD --intent 'Release obsolete build inputs'
/absolute/bin/kode --home /absolute/kode-store gc
/absolute/bin/kode --home /absolute/kode-store gc --prune --intent 'Collect abandoned source attempts'
```

`gc` previews storage and reference-root counts. `gc --prune` expires reflogs and
collects objects unreachable from all remaining refs; it does not delete source
heads, task records, request receipts or retention pins. Detached attempts without
one of those roots can be collected. Maintenance takes an exclusive workspace
lock while ordinary Kode operations take shared locks. Use Kode maintenance for
this coordination; direct Git commands do not participate.

A recorded pin describes what was retained when the run started. It does not
promise that somebody has kept the pin, local cache, dependency artifacts or
compiler installations. Removing a pin does not remove an existing local run
record, and cached input verification remains independent of provider retention.

Kode revision claims appear in the result's `sourceClaims` with their original
JSON declarations and `confirmed`, `refuted` or `unchecked` assessments. Kvik
understands `{"kind":"check-passes","root":"sub","check":"library:jvm:test"}` and
`{"kind":"test-passes","root":"sub","module":"library","target":"jvm","test":"EXACT_TEST_ID"}`.
`root` selects the configuration directory inside the revision; omitting it
means the revision root. Claims for another project directory remain unchecked.
Use a test ID from the structured test results. A failed attempt refutes the
test claim, including a failed attempt followed by a successful retry. Skipped,
missing, unrequested or incomplete checks remain unchecked. Unknown formats,
including ABI and unaffected-module claims, are retained without interpretation.
Assessments describe the recorded run and do not change its success or failure.
This requires Kode's `log --claim-values-as-text` support; cached declarations
are required for offline builds and queries of runs that include assessments.

`resolve` and `check-lock` accept the same source flags. Revision-backed `resolve`
returns a proposal with `changed` and `transaction`; it populates the local
dependency cache without publishing a revision. Pass `--replace-project FILE`
to resolve an edited configuration and include both configuration and lock changes
in one proposal. The file is read once and its exact bytes are embedded in the
proposal. Existing executable modes are preserved. An unchanged result has
`changed: false` and `transaction: null`.
A valid replacement can also repair a malformed or invalid stored configuration.

Apply a changed proposal explicitly, choosing the destination reference:

```sh
./kvik --no-build -- resolve --kode /absolute/bin/kode --kode-home /absolute/kode-store \
  --source-project library --revision main --project sub/kvik.json \
  --lock locks/dependencies.json --store /absolute/cache \
  --replace-project /absolute/edited-kvik.json > proposal.json
jq '.transaction.operations' proposal.json > operations.json
/absolute/bin/kode --home /absolute/kode-store tx library \
  --base "$(jq -r '.transaction.base' proposal.json)" --ref main \
  --expect-head "$(jq -r '.transaction.expectHead' proposal.json)" \
  --intent "$(jq -r '.transaction.intent' proposal.json)" --ops operations.json
```

The proposal pins the base and expected head to the selected immutable revision,
with native blob guards for each edited file. A concurrent head change causes a
conflict; resolve again against the new head or apply to an explicit branch.
`check-lock` validates the selected revision's lock and cached dependencies.
As with local resolution, `resolve --offline` performs this check without
proposing an update. Offline checks need a full revision ID and previously cached
configuration, lock and dependencies; `--replace-project` requires online resolve.

`inspect` includes derived tags on source and resource files. Use `view` to export
a revision-pinned projection for Kode:

```sh
./kvik --no-build -- view --kode /absolute/bin/kode --kode-home /absolute/kode-store \
  --source-project library --revision main --project sub/kvik.json \
  --lock locks/dependencies.json --role production > production-view.json
/absolute/bin/kode --home /absolute/kode-store tree library \
  --rev IMMUTABLE_REVISION --view production-view.json
/absolute/bin/kode --home /absolute/kode-store search library 'TODO' \
  --rev IMMUTABLE_REVISION --view production-view.json --max 20
```

Use the revision in `source.provenance.revision` from the exported view. Kode
rejects a view from another project or revision. `read` also accepts `--view` and
refuses files outside the projection. Search limits count visible matches;
hidden matches do not consume the limit. An empty view stays empty.

Roles `production` and `tests` select the corresponding source/resource scope,
plus configuration and the selected lock; production also includes ABI baselines.
Role `all` includes the whole configuration directory. Repeat `--tag module:NAME`,
`--tag target:jvm` or another tag to require all selected tags; `--exclude-tag TAG`
removes matching files. Source-set inheritance determines scopes: main sources
inherited by test source sets remain production files. Script, plugin-input,
documentation and ABI-baseline tags follow explicit declarations. Tags do not
infer generated status or declaration visibility from filenames; generated build
outputs remain artifacts outside the source revision.

Add exact configuration-relative file paths to `fileTags` in `kvik.json` to layer
manual classifications on top of derived tags:

```json
"fileTags": {
  "docs/architecture.md": ["manual:architecture"],
  "schemas/v1/contracts.json": ["manual:contract"]
}
```

Use `view --role all --tag manual:architecture` with the source options above to
select those files. `inspect` includes existing manually tagged files even outside
source/resource directories, with empty ownership lists when no source set owns
them. Paths are literal files, not globs or recursive directory declarations;
absent files and directory entries are omitted. The metadata remains part of the
selected revision and works with offline views. Manual tags must use the `manual:`
namespace (up to 128 characters), with 1–64 distinct tags per path and at most 4096
paths. They cannot replace derived tags or add compiler inputs.

A view is a display projection. `tx --view` validates the base revision and still
applies to the full tree. `merge --view` validates the target revision, merges full
trees and reports every conflict, with `hiddenConflicts` identifying paths outside
the projection. View filters are not edit permissions. Offline view export needs
a cached immutable inventory and configuration; it does not need source-file bytes.

To restrict a CLI invocation's mutations, export a role policy using the same
source options and pass it to Kode with `--policy`:

```sh
./kvik --no-build -- view --kode /absolute/bin/kode --kode-home /absolute/kode-store \
  --source-project library --revision main --project sub/kvik.json \
  --role tests --policy-name test-author --edit-tag scope:test \
  --allow task-create > test-author.json
/absolute/bin/kode --home /absolute/kode-store write library sub/test/NewTest.kt \
  --base IMMUTABLE_REVISION --content '// new test' --intent 'Add test' \
  --policy test-author.json
```

The policy combines a read view with independent edit rules and capabilities.
`--edit-tag` requires every specified tag; `--edit-any-tag` requires at least one
specified tag; `--exclude-edit-tag` rejects matching files. No edit selection
means read-only. `--allow-all-edits` explicitly permits any file under the source
root, subject to exclusions. New files use classification rules compiled from the
pinned project configuration; renames must permit both paths. Changes to project
configuration do not widen the current policy. Export a new policy for a new base.

Capabilities are explicit: `task-create`, `task-update`, `ref-create`, `ref-update`,
`ref-delete`, `project-add`, and `merge`. A merge also needs `ref-update`, and all
resulting changed paths must pass the edit rules before reference publication.
Transactions still apply to the full revision, including files outside the read
view. Denials identify the policy and rejected rule. Unsupported commands with
`--policy` fail explicitly; `--policy` and `--view` cannot be combined.

The caller chooses and supplies this policy for each invocation. It is a guardrail
for a trusted agent launcher, not an authentication mechanism or a filesystem
sandbox. The file and CLI invocation must be controlled by that launcher for the
restrictions to remain in force. Policies work offline from the same pinned
inventory and configuration used by views.

For several source windows in one call, pass a request file to Kode:

```json
{"schemaVersion":1,"reads":[
  {"path":"sub/src/Main.kt","startLine":40,"endLine":60,"before":3,"after":3},
  {"path":"sub/test/MainTest.kt","startLine":1,"endLine":80}
]}
```

```sh
/absolute/bin/kode --home /absolute/kode-store read-batch library \
  --rev IMMUTABLE_REVISION --reads reads.json
/absolute/bin/kode --home /absolute/kode-store tree-page library \
  --rev IMMUTABLE_REVISION --limit 100
```

Both commands accept `--view FILE`. Batch reads preserve request order and return
each file's blob identity, requested range, actual context range and total lines.
Missing, hidden, binary or oversized files fail the whole request. Each file's
`next` value is a ready-to-use request document for the next window; it includes
the immutable revision. Defaults are 200 lines and no context. Limits are 128
windows, 1,000 requested lines per window, 100 context lines on either side,
8 MiB per file, 32 MiB of unique file bytes and 16 MiB of response JSON.

`tree-page` returns up to `--limit` entries (default 200, maximum 1,000), `total`,
`complete` and `nextCursor`. Pass the returned cursor string as `--cursor` for the
next page. Cursors bind the project, revision, path prefix and view; a moving
reference or changed filter is rejected. Use the returned immutable revision to
continue the original snapshot. Tree pages reduce returned data; the current
implementation still scans the full tree internally.

Search results and revision history also support bounded pages:

```sh
/absolute/bin/kode --home /absolute/kode-store search-page library needle \
  --rev IMMUTABLE_REVISION --fixed --view production-view.json --limit 100
/absolute/bin/kode --home /absolute/kode-store log-page library \
  --rev IMMUTABLE_REVISION --claim-values-as-text --limit 20
```

Pass `nextCursor` back as `--cursor` with the same revision and query options.
Search cursors bind the pattern, matching flags, literal `--path` prefixes and
view; history cursors bind the claims encoding. Page size may change between
requests. `complete` indicates the last page, whose `nextCursor` is null.

`search-page` searches regular text blobs, with one hit per
matching line. Hits include the path, blob identity, mode, line number and
one-based UTF-8 byte column. Hidden matches do not consume a view's page limit.
The default limit is 200 and the maximum is 1,000. Git output is streamed and
stopped after detecting the next page; matching records above 8 MiB or responses
above 16 MiB fail explicitly. The default `--index off` scans file contents
directly. The tree inventory is still scanned, and resuming within a file
rescans that blob.

Build a reusable text index explicitly, then use it to skip blobs that cannot
contain a fixed, case-sensitive pattern:

```sh
/absolute/bin/kode --home /absolute/kode-store index library --rev main
/absolute/bin/kode --home /absolute/kode-store search-page library needle \
  --rev IMMUTABLE_REVISION --fixed --index prefer --view production-view.json
```

`index` resolves the selector once and publishes an index for that immutable
revision. Unchanged content reuses the same blob summaries across revisions,
including renames and duplicate files; `builtBlobs` and `reusedBlobs` report the
work performed. Summaries filter candidates; exact Git searches still establish
the returned matches. Index construction runs in the foreground.

Search responses expose `requestedRevision`, `indexedRevision`, `indexStatus`
and `indexComplete`, separately from result pagination's `complete`.
`indexComplete` means that index coverage was verified for the query's selected
file scope, including its path and view filters.
`--index prefer` falls back to exact scanning when the requested revision's
index is missing or invalid, or the query is unsupported. `--index required`
fails explicitly in those cases. Index filtering supports fixed, case-sensitive
single-line patterns of at least three UTF-8 bytes. Regular expressions,
case-insensitive searches and shorter or multiline patterns use exact scanning.
An old revision's index never stands in for a newly advanced reference.

`searchMode` is `indexed-exact` when filtering is active and `exact-scan`
otherwise. `scannedBlobs` and `skippedBlobs` count selected file entries visited
on that page; view and role-policy filters still govern search results. A
complete, empty result means that the requested search found no matches, even
when it used a fallback scan. Index construction covers the full project and
rejects `--view` and `--policy`; indexed searches accept both in the same way as
ordinary searches.

Index files under the Kode home's `search-index/v1` directory are disposable
derived data. Version, identity and checksum checks detect damaged or misplaced
cache entries; they assume trusted local cache writers. These files do not
retain source revisions or replace retention pins. They can be removed while
Kode is stopped and rebuilt with `index`.

`log-page` traverses the pinned revision's complete ancestry in topological order,
including merge parents. Its default limit is 20 and maximum is 1,000; each
commit's captured log data is limited to 2 MiB and the response to 16 MiB.
`--claim-values-as-text` preserves claim values as JSON text for consumers that
cannot represent every JSON number exactly. History is not filtered by `--view`.

Kode tasks group source experiments into a reviewable change:

```sh
/absolute/bin/kode --home /absolute/kode-store tasks create library fix-parser \
  --base BASE_REVISION --rev FIRST_CANDIDATE --intent 'Handle empty input'
/absolute/bin/kode --home /absolute/kode-store tasks update library fix-parser \
  --expect-record PREVIOUS_TASK_RECORD --rev SELECTED_CANDIDATE \
  --intent 'Handle empty input and preserve valid parsing'
/absolute/bin/kode --home /absolute/kode-store tasks review library fix-parser \
  --record TASK_RECORD --patch
```

The response distinguishes `record` (immutable task metadata) from `revision`
(selected source). Use `revision` with Kvik's `--revision` or Kode's guarded
`merge`/`export` commands. Review returns the final base-to-candidate changes and,
with `--patch`, their patch. It includes the full source tree, including files
outside a role view. The base remains fixed; selecting another branch experiment
or returning to the base creates a new task record without rewriting source history.

`tasks show --record ID` opens a historical selection. Follow `previousRecord`
to inspect earlier attempts, or use `log-page --rev SOURCE_REVISION` for their
individual edits and intents. Task refs retain these metadata records and source
snapshots through Git reachability. `--result REFERENCE` may be repeated to attach
up to 32 opaque run/result references to the selected source revision; Kode does
not validate their contents or retain external artifacts. Each update supplies
its own references, while previous records keep theirs.

Updates require `--expect-record`; conflicting selections fail. Retrying the
same create/update returns the existing record while it remains the latest task
selection. After a later update, a stale retry conflicts and can be reconciled
through historical records. `tasks list library --limit 100 --after TASK_ID`
enumerates live task heads in name order; `nextAfter` continues that listing,
which is not an immutable snapshot. Task IDs are single lowercase name segments.
The implementation scans the ref inventory with an 8 MiB bound. Review limits
captured change data and optional patch data to 8 MiB each and response JSON to
16 MiB; oversized reviews fail explicitly. Task grouping preserves detailed
history and does not squash it into a new source commit.

Kode can propose a text-first identifier rename in `.kt` and `.kts` sources:

```sh
/absolute/bin/kode --home /absolute/kode-store rename-identifier library \
  oldName newName --base IMMUTABLE_REVISION > rename.json
```

The response contains `transaction.base`, `transaction.expectHead`, an intent and
`transaction.operations` in the same format accepted by `kode tx --ops FILE`.
Save that operations object to a file and apply it with `tx --base` and `--intent`,
omitting `--ref` to create a detached candidate. Run Kvik `test` with the candidate's
immutable revision and merge it into the desired reference only after checking
the result. The proposal itself never publishes changes; `transaction` is null
when no identifiers match.

Repeat `--path PREFIX` to restrict the proposal to selected files or directories.
Renames preserve comments, quoted text and executable bits. Affected backtick
identifiers and string interpolation require an explicit edit. This operation
rewrites identifier tokens without resolving symbols: a new name can collide
with another declaration, and limited path selection can leave references behind.
Compilation and tests on the complete candidate are required to check it.

Compiler plugins can be built from another module in the same project. Declare
its module, plugin ID, compiler version and selected targets/scopes on the consumer;
see [workspace compiler plugins](docs/workspace-compiler-plugins.md).

For a runnable target, declare `application` with an `entryPoint`, optional ordered
`arguments` and an `environment` map. JVM entry points name the main class, such
as `example.MainKt`; macosArm64 entry points name the Kotlin function, such as
`example.main`. Select exactly one module and target:

```sh
./kvik --offline --no-build -- run-application --project /absolute/project/kvik.json \
  --tools /absolute/project/tools.json --module app --target jvm
```

`prepare-application` accepts the same options and only prepares the bundle.
`run-application` prepares it and starts a fresh owned process. Preparation may
reuse cached compilation and packaging; application execution always runs. To
replay a preparation, pass its `application-prepared` event's `directory` and
`descriptor` to `run-prepared --run PATH --descriptor DIGEST_JSON`. Replay checks
the bundle, completed evidence and current sources/tools before starting.
The application uses the project directory, declared environment and EOF stdin;
JSON events identify its retained stdout/stderr files. Its exit code propagates,
and interruption stops its owned process group. See the [application contract](docs/decisions/k15-application-lifecycle.md)
and [JVM/Native acceptance evidence](docs/evidence/k15-application-cli.md).

For continuous application work, select application mode explicitly:

```sh
./kvik --offline -- dev --mode application --project /absolute/project/kvik.json \
  --tools /absolute/project/tools.json --module app --target jvm
```

In a terminal, `r` requests reload, `t` runs fresh tests, `q` exits and `?` shows
help. File watching also requests reloads. A failed preparation keeps the previous
application running; status identifies when it belongs to older inputs. A ready
replacement starts only after the previous owned process tree has stopped. Test requests are retained
independently of reload requests. `--initial test` requests tests before the first
application preparation. Without `--mode application`, `dev` uses library mode.

Agents and other non-TTY callers must pass `--control jsonl` (versioned controls
and events) or `--control lines`. Status includes the active application identity,
candidate state and queued intents; closing input stops the session's children.
Source changes to Kvik or its build plugins trigger a new native composition and
an explicit session handoff. See the [foreground controller and acceptance
status](docs/evidence/k15-foreground-applications.md); the full public JVM/Native
replacement and compiled-source handoff gate is still being validated.

Dependencies default to private compile dependencies. Set `exported: true` when a dependency belongs on consuming modules' compile classpaths; use `scope: "runtime"` for runtime-only dependencies. Runtime closures include private dependencies. Target `testCompilerOptions` optionally replaces `compilerOptions` for test compilation, so production-only settings such as strict explicit API need not apply to tests. Omission preserves inheritance; an empty list explicitly selects default test options.

Native targets can set `nativeTimeouts`, for example `{"linkMillis": 600000}` for a large test executable. Optional `compileMillis` and `linkMillis` values are integer milliseconds from 1 to 3,600,000; omitted phases retain their 180,000 ms limits. These settings affect compiler actions, while KSP and test execution keep their separate budgets. A compiler timeout reports `KVIK_NATIVE_TIMEOUT` with the phase and configured limit; partial outputs are not cached. See [Native timeout settings and checks](docs/evidence/k08-native-timeouts.md).

Native `compilerOptions` also accept `-Xreturn-value-checker=check|full` and `-Xname-based-destructuring=only-syntax|name-mismatch|complete` (choose one value per option). They apply to source compilation, participate in cache identity and follow the ordinary `testCompilerOptions` inheritance rules. JVM, shared metadata and KSP adapters currently reject these settings explicitly.

On macOS ARM64, `iosArm64` and `iosSimulatorArm64` library targets can compile KLIBs and link frameworks. Add `"nativeFramework": {"baseName": "Sample", "isStatic": false}` to a target with `"test": null`, then run `build --module library --target iosSimulatorArm64` with the usual project and tools options. The `framework-ready` event returns the materialized framework path and, for a dynamic framework, its dSYM path. `isStatic` defaults to `false`. Framework linking includes runtime dependencies; changing the framework name or static setting reuses unchanged KLIBs. `package-library` continues to publish KLIBs.

Pin the consumer SDKs explicitly with `python3 bootstrap/pin_tools.py --java-home "$JDK" --konan-home "$KONAN" --apple-target iosArm64 --apple-target iosSimulatorArm64 --output /absolute/project/ios-toolchains.lock.json`. Put that lock in the consumer `tools.json` `toolLock` field and add matching `iosSdk` and `iosSimulatorSdk` roots from `xcrun --sdk iphoneos --show-sdk-path` and `xcrun --sdk iphonesimulator --show-sdk-path`. The SDKs must belong to the pinned Xcode installation. Fresh compilation verifies the selected SDK; cached frameworks can be restored without installed compiler tools. This support covers compilation and linking: framework resources, dependency API exports, XCFramework assembly, iOS test execution and app deployment are not yet supported.

Dependency locks use schema version 2 and resolve each module, target, compilation and usage separately. Existing unscoped locks require an explicit `resolve`. Repeatable `--scope-pin MODULE:TARGET:COMPILATION:USAGE:PURPOSE=GROUP:ARTIFACT:VERSION` overrides a global pin only in that domain; for example, `--scope-pin library:jvm:test:java-api:dependencies=org.jetbrains:annotations:23.0.0`. Supply it consistently to `resolve`, `check-lock`, `inspect`, `build` and `test`; runtime usage is a separate domain. Unknown domains and duplicate component pins are errors. Native test source compilation and executable linking use separate API and runtime closures; private link dependencies remain unavailable to source compilation. Source-free links reject missing used symbols. See [Native visibility evidence and portability limits](docs/evidence/k08-native-visibility.md).

The source bootstrap uses Python with `os.waitid`/`WNOWAIT` support (tested with Python 3.15). A cache hit verifies and executes the native binary without starting a JVM. A cache miss verifies the installed compiler, JDK, native dependencies and Apple tools against `bootstrap/toolchains.lock.json`; it does not download missing tools. That lock currently describes the development macOS ARM64 host, so another host must be explicitly provisioned and repinned with `bootstrap/pin_tools.py` before building.

For a known incorrect published dependency edge, project commands accept repeatable `--exclude-metadata-edge JSON`. Each rule identifies the exact consumer domain, parent coordinate, metadata digest, selected variant, declared dependency and reason. Rules are part of the configuration and lock identity: pass them consistently to `resolve`, `check-lock`, `inspect`, `build`, `test`, `dev` and `dependencies`. The project dependency query shows corrected edges and their reasons. Raw `packages dependencies` queries inspect published metadata and do not apply project rules. See [the metadata correction contract](docs/decisions/metadata-edge-corrections.md).

Bootstrap compiler commands have a 300-second deadline by default. On slower hosts, pass `--bootstrap-command-timeout 1800` before `--` (integer seconds, 1–3600). Foreground recomposition preserves this setting and scales its total rebuild budget proportionally above the default 15 minutes. The setting does not change artifact identities or consumer-action deadlines.

The bootstrap also compiles the JVM compiler/test workers from source and bundles their classes, three runtime libraries and the JaCoCo agent/reporter alongside the native executable. Their 17 compiler/library artifacts are checksum-pinned in `bootstrap/jvm-artifacts.lock.json`; missing jars are acquired from their locked Maven URLs unless `--offline` is set. Use `--artifact-cache PATH` to select the artifact directory (default `.kvik/tool-artifacts`). Cached startup needs neither compiler installation nor the separate compiler-artifact directory. JVM workers are invoked only for tool operations; Kvik and its source build plugins run as Kotlin/Native.

Select an explicit source composition with `--source-lock /absolute/source.lock.json` and optionally `--source-cache /absolute/source-cache` (default `.kvik/source-cache`). The [source graph contract](docs/decisions/k14-source-graph.md) covers pinned Git/archive packages, local source packages, source plugin dependencies and exact Native library closures. `--print-composition` validates declarations and observes local inputs without downloading or creating caches. An allowed build acquires verified inputs and compiles the composition; `--offline` requires cached remote inputs, and `--no-build` requires an already verified executable. Without `--source-lock`, the existing local composition remains the default. The [full HTTPS source composition fixture](docs/evidence/k14-source-bootstrap.md) passes cold build, relocated/offline reuse and rebuild, and rebuilding after a shared helper changes; its runtime also passes the existing JVM consumer tests.

Use `--runtime-store /absolute/shared-store` to share complete verified native runtime bundles between checkouts or distinct `--cache-dir` directories on the same compatible host. The local K04 store includes the executable, guardian, JVM workers and runtime manifests. Concurrent launchers coordinate one build; `--offline --no-build` can restore an existing shared bundle into an empty local runtime cache without compiler or source/artifact caches. Local source declarations still need to be observable. The store must be separate from sources, other bootstrap caches and tool installations. See the [shared runtime contract](docs/decisions/k14-shared-runtime-store.md) and [actual build/restore evidence](docs/evidence/k14-shared-runtime-launcher.md).

`abi-dump` and `abi-check` accept the same module, target, project and tools selectors as `build`. They read main compilation outputs on JVM and Native targets; publication coordinates are optional. Add `"abi": {"baseline": "api/library.jvm.api"}` to each target you want to check, using a separate baseline per target.

Run `abi-dump --module library --target jvm` with your project and tools options to produce the first candidate. The `abi-ready` event provides paths to `api.txt` and its versioned `abi.json` metadata. Review `api.txt` and copy it to the configured baseline, then use `abi-check` in your normal verification workflow. Neither command updates baselines automatically. A failed comparison still exposes the current candidate for review.

Checks require an exact public API match, allowing CRLF/LF and an optional final newline in the baseline. Additions and removals both fail; this is not a binary compatibility classification. Successful extraction and comparisons are cached locally. Baseline edits invalidate comparison and retained verification freshness while preserving compilation and extraction caches. ABI readers use pinned BCV and Kotlin compiler versions in an owned, isolated JVM process.

Enable API documentation with `"documentation": {}` on a module, then run `resolve` to capture its documentation dependencies. `docs --module library --target jvm --target macosArm64` accepts the usual project and tools options and creates one HTML site for the selected module and targets. The `documentation-ready` event gives the site's directory and `index.html`. Documentation consumes source-set relationships and compiled dependency artifacts from the same graph as builds; publication coordinates are optional.

Module documentation settings support `includes` (Markdown files), `documentedVisibilities`, `reportUndocumented`, `failOnWarning`, `skipDeprecated`, `suppressInheritedMembers`, and `timeoutMillis`. For example, `"documentation": {"includes": ["Module.md"], "reportUndocumented": true, "failOnWarning": true}` enables strict documentation checks. Include edits invalidate the site and retained verification freshness while preserving compilation caches. Failed or cancelled generation never exposes a completed site.

Dokka 2.2.0 runs offline in an owned JVM, using a separate pinned tool distribution. Common metadata and Native stdlib are declared inputs. Successful sites are cached locally and can be restored without installed tools. This command generates per-module HTML for the supported JVM/Native source sets; aggregation, custom Dokka plugins, external documentation links, SDK/cinterop documentation and deployment need separate integrations.

## Current checks

On macOS ARM64, supply a Kotlin/Native 2.4.20 installation and Liberica JDK 25.0.4:

```sh
python3 experiments/k01/bootstrap.py --java-home "$JDK" --konan-home "$KONAN"
# Set BUILD to the directory printed above.
python3 experiments/k01/verify.py --build-dir "$BUILD" --java-home "$JDK" --konan-home "$KONAN"
python3 tools/test_contracts.py --java-home "$JDK" --konan-home "$KONAN"
```

The bounded spike downloads checksum-locked compiler artifacts and compiles sources directly. It does not call Gradle or Kotlin Toolchain to compile consumer code. The reference baseline separately uses the fixture's existing KTC build for comparison.

Schema validation uses development-only Python dependencies:

```sh
python3 -m venv .kvik/schema-tools
.kvik/schema-tools/bin/pip install -r tools/schema-requirements.txt
.kvik/schema-tools/bin/python tools/check_schemas.py
```

Upstream source submodules under `references/` are optional design references, not runtime/bootstrap dependencies. See [their provenance](references/README.md).

### Inspect and control a foreground session

The `session-started` event from `dev` supplies its directory. Another terminal
or agent can query it without taking over the owner's stdin:

```sh
kvik session status --session /project/.kvik/sessions/SESSION
kvik session events --session /project/.kvik/sessions/SESSION --limit 100
kvik session control --session /project/.kvik/sessions/SESSION --request-id test-1 --command t
kvik session control --session /project/.kvik/sessions/SESSION --request-id cancel-1 --command cancel --operation OPERATION_ID
```

Pass `nextCursor` as JSON to `session events --cursor` for subsequent pages.
Only complete journal records advance the cursor; reads and page sizes are
bounded. Status and event queries remain available after exit. `ownerAlive`
is a lock observation at query time; the saved state may describe the last
observation before a crash. Runtime recomposition exposes `nextSessionDirectory`;
clients explicitly reconnect to that new directory with a new event cursor.

Control commands are `r`, `t`, `q`, `status` and `cancel`. Each request requires an
ID: repeating the same ID and command retrieves its original acknowledgement,
while changing its arguments is rejected. Cancellation requires the immutable
`operationId` exposed by operation events or application status. It cannot cancel
a newer operation that happens to occupy the same slot. An acknowledgement
confirms receipt, not successful compilation or completed cancellation. Its
`outcome.status` is `accepted`, `not-active` or `closing`; the latter two reject
the requested action. Follow the operation's events for its final outcome.

`--wait-ms` defaults to 1000 and accepts 0–10000. Exit status 3 with `pending`
means the command was submitted but not acknowledged within the wait; retry
with the same ID. `busy` means publication was blocked and this attempt did not
submit a command. `owner-exited` means no acknowledgement was observed before
owner exit; it does not prove the command never ran. A retained acknowledgement
can still be retrieved after exit.

The transport lives in the session's private local directory and is processed
by the foreground owner. Its lock is released on exit and runtime replacement.
It starts no service, and clients do not extend the owner's lifetime: stdin EOF,
terminal loss, output backpressure and normal shutdown retain their existing
behavior. The directory has the same trusted-local-filesystem boundary as the
artifact store; it is not a remote authorization protocol.
