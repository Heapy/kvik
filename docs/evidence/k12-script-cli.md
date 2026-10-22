# K12 public script CLI integration

This isolated candidate connects named project scripts and standalone `.kt` files to
dependency locks, snapshots, the compilation graph, local compilation reuse and fresh
owned JVM execution. Main integration and the full workspace/plugin/lifecycle acceptance
gate remain pending.

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
The corrected full CLI still requires a rebuilt-runtime replay; the wider public
workspace-module/plugin, invalidation and failure/lifecycle gate remains pending.
