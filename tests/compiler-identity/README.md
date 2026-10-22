# Compiler distribution order acceptance preparation

This is a source-only optional `compiler-identity` phase for
`tools/test_scripts_cli.py`. Actual execution needs two separately built runtimes;
this module performs no runtime build or source mutation. No public acceptance is
claimed by the preparation checks.

## Dispatcher interface

Import sibling `script_acceptance_compiler_identity` and add its phase. Keep the
existing standalone fixture, script name `plain`, empty resolver policy and
`Main.kt` selector. Call `prepare(project)` after default preparation. Expose
`--alternate-runtime PATH`, resolving it to the actual manifest-bound `kvik.kexe`.
Receipt-bind both runtime identities, this module, and its full `FIXTURES`
inventory at invocation and finish. `run(gate)` copies the argument namespace
before switching runtimes and restores the original namespace in `finally`.

The actual sequence uses one unchanged project, dependency lock, CAS and runs
directory: A cold, A warm, B cold, B warm, A reuse. All five executions must produce
fresh effects. Only the two cold compilations execute; the other three must be
local hits. Every run uses the normal public `run-script` CLI and full Gate
schema/journal/worker-request audit. Each completed record gets a current read-only
verification query under its corresponding runtime.

## Alternate runtime source and trust

Use a fresh copy/worktree of the exact approved A production commit. In its
`bootstrap/jvm-artifacts.lock.json`, swap only the final two `compilerArtifacts`
entries: `kotlinx-coroutines-core-jvm-1.8.0.jar` and `annotations-13.0.jar`. Preserve
all coordinates, filenames, URLs, sizes and hashes. Rebuild through the ordinary
bootstrap with verified original artifacts. Do not edit a compiled manifest,
inject a compiler identity, repack a Maven artifact, or relabel arbitrary bytes as
an official release.

Retain an independent A/B source inventory and build receipt proving the sole
source delta, including actual compiler commands and environment. The acceptance
module checks runtime-lock fields (only the exact library transposition and
recipe digest may differ), but a recipe digest alone cannot prove its source
inputs. This external receipt is a required part of the final evidence review.

The module verifies both complete JVM worker class trees and requires identical
worker bytes, API, stdlib, JUnit and worker identity. Compiler distributions must
differ by precisely that ordered transposition; every original compiler JAR and
the plugin are rehashed against their pins in the supplied artifact directory.
Both actual cold worker requests must name the expected distribution identity.
The ordered array is consumed by `KotlinToolchains.loadImplementation`, so the
changed value is an actual compiler input rather than unused metadata.

## Causality and limits

Both compilation outputs must be byte-identical. For both compile and execution
actions, the only permitted field differences are `composition` and
`toolchains.jvm-compiler`; JDK, worker, JUnit, sources, arguments, options and
declared inputs must be unchanged. The gate retains an explicitly labeled
counterfactual canonical-action calculation changing only the compiler identity,
with composition held fixed. It is **not** labeled an executed action. Root's
separate Native graph test supplies the actual fixed-composition model assertion.

The public cache miss alone is confounded by the necessarily different runtime
composition; the normalization and model test make that limit explicit. This
gate proves sensitivity to a changed **ordered compiler distribution identity**.
It does not prove compiler implementation byte changes, alternate compiler
versions, custom distributions, or generalized compatibility. Current production
provider and loaded BTA checks still require Kotlin 2.4.20.

Pure preparation guard checks:

```sh
python3 -B tests/compiler-identity/test_preparation.py -v
```
