# Kvik

An experimental Kotlin Multiplatform build tool, distributed with its build plugins as sources and compiled into a cached **Kotlin/Native** executable. Invocations own their workers; repeated development work uses an explicit foreground session.

The launcher compiles Kvik and its local source plugin into one Kotlin/Native executable. Its current commands include `help`, `runtime-info`, `inspect`, `resolve`, `check-lock`, `build`, `test`, `run-script`, `inspect-script`, `resolve-script`, `check-script-lock`, `prepare-application`, `run-application`, `run-prepared`, `dev`, `verification`, `test-report`, `test-aggregate`, `coverage-report`, `coverage-check`, `coverage-aggregate`, `coverage-export`, `explain`, `packages versions`, `packages dependencies` and `dependencies`. Consumer commands connect the locked project graph to the JVM/Native adapters, local compilation cache, [remote JVM compilation cache](docs/remote-cache.md) and retained run results. [Foreground sessions](docs/foreground.md) retain project models and action graphs across reloads. See [PLAN.md](PLAN.md), [current status](docs/STATUS.md), [build command contract and limits](docs/evidence/k09-build-cli.md), [launcher evidence](docs/evidence/k14.md) and [v1 contracts](docs/decisions/0002-contracts.md).

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

Use the [coverage commands](docs/coverage.md) to capture JVM line coverage, generate retained reports, check thresholds, aggregate compatible test checks and export SonarQube coverage XML or unchanged JaCoCo reports.

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

Dependency locks use schema version 2 and resolve each module, target, compilation and usage separately. Existing unscoped locks require an explicit `resolve`. Repeatable `--scope-pin MODULE:TARGET:COMPILATION:USAGE:PURPOSE=GROUP:ARTIFACT:VERSION` overrides a global pin only in that domain; for example, `--scope-pin library:jvm:test:java-api:dependencies=org.jetbrains:annotations:23.0.0`. Supply it consistently to `resolve`, `check-lock`, `inspect`, `build` and `test`; runtime usage is a separate domain. Unknown domains and duplicate component pins are errors. Native test source compilation and executable linking use separate API and runtime closures; private link dependencies remain unavailable to source compilation. Source-free links reject missing used symbols. See [Native visibility evidence and portability limits](docs/evidence/k08-native-visibility.md).

The source bootstrap uses Python with `os.waitid`/`WNOWAIT` support (tested with Python 3.15). A cache hit verifies and executes the native binary without starting a JVM. A cache miss verifies the installed compiler, JDK, native dependencies and Apple tools against `bootstrap/toolchains.lock.json`; it does not download missing tools. That lock currently describes the development macOS ARM64 host, so another host must be explicitly provisioned and repinned with `bootstrap/pin_tools.py` before building.

For a known incorrect published dependency edge, project commands accept repeatable `--exclude-metadata-edge JSON`. Each rule identifies the exact consumer domain, parent coordinate, metadata digest, selected variant, declared dependency and reason. Rules are part of the configuration and lock identity: pass them consistently to `resolve`, `check-lock`, `inspect`, `build`, `test`, `dev` and `dependencies`. The project dependency query shows corrected edges and their reasons. Raw `packages dependencies` queries inspect published metadata and do not apply project rules. See [the metadata correction contract](docs/decisions/metadata-edge-corrections.md).

The bootstrap also compiles the JVM compiler/test workers from source and bundles their classes, three runtime libraries and the JaCoCo agent/reporter alongside the native executable. Their 17 compiler/library artifacts are checksum-pinned in `bootstrap/jvm-artifacts.lock.json`; missing jars are acquired from their locked Maven URLs unless `--offline` is set. Use `--artifact-cache PATH` to select the artifact directory (default `.kvik/tool-artifacts`). Cached startup needs neither compiler installation nor the separate compiler-artifact directory. JVM workers are invoked only for tool operations; Kvik and its source build plugins run as Kotlin/Native.

Select an explicit source composition with `--source-lock /absolute/source.lock.json` and optionally `--source-cache /absolute/source-cache` (default `.kvik/source-cache`). The [source graph contract](docs/decisions/k14-source-graph.md) covers pinned Git/archive packages, local source packages, source plugin dependencies and exact Native library closures. `--print-composition` validates declarations and observes local inputs without downloading or creating caches. An allowed build acquires verified inputs and compiles the composition; `--offline` requires cached remote inputs, and `--no-build` requires an already verified executable. Without `--source-lock`, the existing local composition remains the default. The [full HTTPS source composition fixture](docs/evidence/k14-source-bootstrap.md) passes cold build, relocated/offline reuse and rebuild, and rebuilding after a shared helper changes; its runtime also passes the existing JVM consumer tests.

Use `--runtime-store /absolute/shared-store` to share complete verified native runtime bundles between checkouts or distinct `--cache-dir` directories on the same compatible host. The local K04 store includes the executable, guardian, JVM workers and runtime manifests. Concurrent launchers coordinate one build; `--offline --no-build` can restore an existing shared bundle into an empty local runtime cache without compiler or source/artifact caches. Local source declarations still need to be observable. The store must be separate from sources, other bootstrap caches and tool installations. See the [shared runtime contract](docs/decisions/k14-shared-runtime-store.md) and [actual build/restore evidence](docs/evidence/k14-shared-runtime-launcher.md).

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
