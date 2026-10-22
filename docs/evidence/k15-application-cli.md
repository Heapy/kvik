# Public JVM and Native application acceptance

This gate validates artifact preparation and explicit, owned application invocation
through the composed Kotlin/Native CLI. It does **not** establish application watch,
automatic replacement, crash-recovery/reload, or runtime recomposition while an
application is running. Those continuous-session gates remain separate work.

## Runtime, fixture and retained execution

The final runtime comes from root CLI commit `34e3c79` (on `3ececa0`). Its composition
is `766140dfa3908148ea006b4b520763f99df118be16b3555ed12b67fcf798cda6` and its actual
`kvik.kexe` digest is
`806e09986561290139c5b0efbf8aaa95a99ddcf4ed088769eb7c341e1bb90fcb`, 4,943,304 bytes.
It is retained under:

```
/private/tmp/kvik-k15-application-cli/.kvik/application-runtime/executables/766140dfa3908148ea006b4b520763f99df118be16b3555ed12b67fcf798cda6/
```

`tests/application-cli/fixture` contains four Kotlin files and three resource files.
The harness copies and hashes the entire seven-file fixture before execution and
checks it again afterwards. It generates a declarative three-module project:
`app` calls `library`, which privately calls `support`. The application also declares
`support` and serialization core 1.11.0 as runtime-only dependencies. Support uses
`SerializationException` at runtime, proving the selected external library is used.
The app compilation excludes the private/runtime-only module and serialization
artifacts; the runtime closure includes them. Kotlin stdlib 2.4.20 and annotations
13.0 are explicit pins. No Gradle/KTC consumer execution or dependency publication
is used.

The successful evidence is deliberately split across two invocations:

- Initial gate, frozen harness `240a9c7`: terminal handle `8238`, retained directory
  `/private/tmp/kvik-k15-application-acceptance/.kvik/tests/application-cli/case-dao5fqft`.
  Both targets completed cold preparation, warm preparation and two fresh program
  executions. The gate then stopped at an incorrect harness expectation that
  `resolve --offline` could update a lock after application settings changed.
- Continuation, frozen harness `e25957e`: terminal handle `18192`, exit 0, directory
  `case-dao5fqft/continuation-jqixx3ht`. Resume is restricted to the known initial
  harness hash and known failure state. It binds the old invocation, runtime,
  schema, fixture and tool document; rechecks all earlier action keys, CAS files,
  descriptors, materialized bundles, ordered classpath and execution logs; repeats
  the boundary process probes; then performs the remaining acceptance phases.
  It does not label re-audited phases as newly executed, or rerun their compilers.

The initial harness SHA-256 is
`e89c905318457efe8291e5c3b4275e8a264135cfbcb187dad7d4638342b85e24`.
The continuation harness SHA-256 is
`8ceb29991950bb6ff1a94311a6b05bf7ab2fc652584d372a4960b7dc80322122`.
The producer schema is copied into each evidence directory and bound by digest
`d9e0d016c517311bffabdf01a1043dc7436834ecaf48beda53c27d95ebd2e93d`, 90,234 bytes.
The final continuation `summary.json` has SHA-256
`02deae3b8951700818977fa6e0c743ff1d3ef100c6d1d512cca7539faacafbcf`.

## Assertions and results

- JVM cold preparation executes four actions; Native cold preparation executes
  five (three klibs, source-free PROGRAM link, package). Preparation writes no
  application effects. Each warm preparation reuses every action locally and
  still starts a fresh program. Native output policy remains LOCAL.
- JVM starts `fixture.ApplicationKt`; Native actually links and invokes the
  custom function `fixture.launchApplication`, with no `main` function in that
  fixture. Native linking consumes the included main klib and complete private
  runtime closure. Producer output digests must match each consumer binding.
- JVM's **entire ordered** classpath is checked: application classes/resources,
  library classes, support classes/resources, then the exact pinned stdlib,
  annotations and serialization jars. Competing `ordered.txt` resources prove
  application resource precedence. Native reads resources at the explicit
  `KVIK_APPLICATION_INPUTS/runtime/...` paths; no classloader claim is made.
- Both actual programs verify ordered literal `@literal.args`, empty, spaced,
  Unicode and dash-prefixed arguments; the real `literal.args` file must not be
  expanded. They check declared environment, absence of poisoned ambient
  variables, fixed PATH/locale/timezone, project-root cwd and stdin EOF. Application
  stdout/stderr are exact raw log files, separate from schema-validated JSONL.
- Changing arguments and environment changes only each package action key.
  All main compilation and Native link keys remain equal and locally reused.
  The changed settings are executed, not merely inspected. The explicit offline
  lock refusal is retained and checked before the authorized online resolve.
- Each target exits 7 through its real program, with a separate application-finished
  event and no cancellation/timeout. Passed preparation is not treated as a passed
  application run. Each preparation record has complete zero test counts.
- Ten replay refusals cover each target's descriptor bytes, prepared resource
  bytes, committed journal, malformed tool-installation document, and changed
  declared source. No application-started event or extra program effect is allowed.
  Original bytes/modes are restored. The tool probe corrupts the installation
  **document**; it does not mutate the user's installed JDK/Native files or claim a
  new test of every installed-byte corruption case.
- Public preparation-boundary JVM probes send SIGINT or change a declared source
  after observing application-prepared. They return 130/3 with no application
  start. The continuation repeats these real process probes and retains their
  explicit result receipts; it does not infer their exit codes from old journals.
- Eight actual lifecycle probes cover SIGINT, SIGTERM and owner SIGKILL for both
  targets, then two concurrently live independent owners. Each process creates
  an application child and grandchild. Before cancellation, the harness records
  the owner-descendant table, guardian PID and application PGID. After cancellation
  it checks all captured processes and every live member of the owned group.
  Cancelling one independent owner leaves the other's application, descendants
  and guardian alive until separately stopped. Observed cleanup took
  0.333–0.404 seconds. SIGKILL cases correctly have no fabricated finished event.

A final read-only tally of the retained case rehashed 43 action specifications and
28 distinct referenced input/output CAS blobs across 10 preparation records.
The six principal cold/warm/settings records contain 9 cold executed actions,
9 warm local hits, and 2 changed packages plus 7 local hits respectively. Four
boundary preparations account for the other 16 local-hit action records.
There are 16 real application-started events, 14 finished events (two owners were
SIGKILLed), and eight effect entries per target. Current verification queries pass
for both final preparations after the corruption probes; final fixture bytes are
also unchanged after lifecycle execution.

## Preserved failures and corrected protocol

The earlier `case-_98j7cr9` under the same harness output root is retained. Runtime
composition `dc05de555fafd592431343b3ed996c4db5f199ba3cf2a50f65018718e55e9602`
executed all four JVM actions and materialized a bundle, but the core RunEvent
allowlist rejected application-prepared. It produced
`KVIK_APPLICATION_PREPARATION_FAILED` and exit 1; no application success is claimed
for that case. Root fixed the three lifecycle event names in `34e3c79` and added a
strict protocol regression. The actual Native contract receipt is:

```
/private/tmp/kvik-k15-application-cli/.kvik/tests/contracts/case-c4ikipwv/receipt.json
SHA-256 fcf7d1e2ca5df25c3ec19a08d03a32543c39bed77d8452743a4d041f596fbced
```

That receipt records 35 passing Native tests, matching source/compiler inputs,
binary and log. The passing application runtime was rebuilt after that fix.

The later offline settings-resolution failure is an acceptance-harness policy
error, not a resolver failure: offline resolution verifies the existing lock.
Its original command/stdout/stderr remain untouched. Continuation uses an explicit
online resolve for the changed effective configuration. Process-table permission
is checked before new expensive work; the successful continuation ran with the
required read-only `ps` permission. It did not fail or retry owned lifecycle
processes because of a late permission denial.

## Reproduction and limits

Fresh gate (requires the exact runtime/schema plus installed tools and initial
network access for explicit resolution; process-table access must be allowed):

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_application_cli.py \
  --runtime /private/tmp/kvik-k15-application-cli/.kvik/application-runtime/executables/766140dfa3908148ea006b4b520763f99df118be16b3555ed12b67fcf798cda6/kvik.kexe \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --artifact-cache /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts \
  --schema-file /private/tmp/kvik-k15-application-cli/schemas/v1/contracts.json
```

The historical continuation used the same command with
`--resume-case /private/tmp/kvik-k15-application-acceptance/.kvik/tests/application-cli/case-dao5fqft`.
That mode is intentionally a narrowly checked recovery point; a now-completed case
cannot be replayed as if its extra application effects had not happened.
`--prepare-only` validates/writes fixture inputs and never claims runtime acceptance.

Native installed-tool revalidation currently costs substantial time: initial warm
prepare-plus-run took 62.291 seconds; settings preparation with cached compilation
and linking took 48.407 seconds; separate Native replay/query operations took about
24–37 seconds. This gate proves correctness and reuse, **not fast Native startup**.
It makes no remote-cache, relocatable-binary determinism, generic JVM/Native
application compatibility, or arbitrary escaped-process ownership claim. The
fixture descendants stay in the owned process group. Continuous dev-mode
application lifecycle remains unclaimed.
