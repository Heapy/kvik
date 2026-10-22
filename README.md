# Kvik

An experimental Kotlin Multiplatform build tool, distributed with its build plugins as sources and compiled into a cached **Kotlin/Native** executable. Invocations own their workers; repeated development work will use an explicit foreground session.

The launcher compiles Kvik and its local source plugin into one Kotlin/Native executable. Its current commands include `help`, `runtime-info`, `inspect`, `resolve`, `check-lock`, `build`, `test`, `dev`, `verification`, `packages versions`, `packages dependencies` and `dependencies`. Consumer commands connect the locked project graph to the JVM/Native adapters, local compilation cache and retained run evidence. See [PLAN.md](PLAN.md), [current status](docs/STATUS.md), [build command contract and limits](docs/evidence/k09-build-cli.md), [launcher evidence](docs/evidence/k14.md) and [v1 contracts](docs/decisions/0002-contracts.md).

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

`build` and `test` always consume the existing lock and cached dependencies without network resolution. Select modules and targets explicitly with repeatable flags. JVM test targets declare `testSettings.classes` and optionally `testSettings.engines` (`junit` by default). Tests execute on every invocation; successful compilation can be reused locally. `--tools` names the explicit installed-tool configuration described in the build command contract. Run stdout contains versioned JSON events; compiler/test streams are retained in the run directory. `verification` checks whether a retained result still describes current inputs and tools.

Dependencies default to private compile dependencies. Set `exported: true` when a dependency belongs on consuming modules' compile classpaths; use `scope: "runtime"` for runtime-only dependencies. Runtime closures include private dependencies. Target `testCompilerOptions` optionally replaces `compilerOptions` for test compilation, so production-only settings such as strict explicit API need not apply to tests. Omission preserves inheritance; an empty list explicitly selects default test options.

Dependency locks use schema version 2 and resolve each module, target, compilation and usage separately. Existing unscoped locks require an explicit `resolve`. Repeatable `--scope-pin MODULE:TARGET:COMPILATION:USAGE:PURPOSE=GROUP:ARTIFACT:VERSION` overrides a global pin only in that domain; for example, `--scope-pin library:jvm:test:java-api:dependencies=org.jetbrains:annotations:23.0.0`. Supply it consistently to `resolve`, `check-lock`, `inspect`, `build` and `test`; runtime usage is a separate domain. Unknown domains and duplicate component pins are errors. Native linking with different visible-API and private-link dependency closures still requires an adapter extension and is rejected explicitly.

The source bootstrap uses Python with `os.waitid`/`WNOWAIT` support (tested with Python 3.15). A cache hit verifies and executes the native binary without starting a JVM. A cache miss verifies the installed compiler, JDK, native dependencies and Apple tools against `bootstrap/toolchains.lock.json`; it does not download missing tools. That lock currently describes the development macOS ARM64 host, so another host must be explicitly provisioned and repinned with `bootstrap/pin_tools.py` before building.

The bootstrap also compiles the JVM compiler/test workers from source and bundles their classes and three runtime libraries alongside the native executable. Their 15 compiler/library artifacts are checksum-pinned in `bootstrap/jvm-artifacts.lock.json`; missing jars are acquired from their locked Maven URLs unless `--offline` is set. Use `--artifact-cache PATH` to select the artifact directory (default `.kvik/tool-artifacts`). Cached startup needs neither compiler installation nor the separate compiler-artifact directory. JVM workers are invoked only for tool operations; Kvik and its source build plugins run as Kotlin/Native.

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
