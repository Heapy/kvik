# Restricted task-effects compiler experiment

This explores the `@Task` research item in `PLAN.md`. Kvik builds the checker from
the `checker` workspace module, then loads it into the owned Kotlin compiler
worker for a separate consumer. The checker is JVM-hosted; consumer code can
target JVM or Kotlin/Native. No intermediate Maven publication is needed.

```kotlin
@Task
fun count(value: Int): Int = increment(value)

fun increment(value: Int): Int = value + 1

@Task(environment = ["BUILD_LABEL"])
fun label(): String? = System.getenv("BUILD_LABEL") // JVM only

@Task(standardOutput = true)
fun announce() { println("building") }
```

The IR checker follows calls into top-level helpers in the same compilation,
using the originating task's permissions. A helper's annotation cannot grant
its caller additional permissions. Diagnostics identify the source location and
call path. A visited set bounds recursive analysis; it does not prove termination.

The initial subset accepts scalar parameters, locals and results, basic integer
arithmetic and control flow. Standard output requires `standardOutput=true`;
JVM clock reads require `clock=true`; JVM environment reads require an exact
permitted literal key. Dynamic keys are rejected even if their runtime value might
match a declaration. Environment values and clock observations are uncontrolled
inputs until an execution adapter captures or constrains them.

Calls outside the explicitly recognized primitives and same-compilation helper
graph are rejected. This includes file/network APIs, reflection, library helpers,
native externals and indirect callbacks. Receivers, suspension, default arguments,
non-scalar signatures and module-level properties are unsupported. The last rule
avoids overlooking effects in file initialization. These restrictions deliberately
exclude many ordinary Kotlin programs; compile failures must distinguish an
unsupported construct from a missing effect permission.

Run the actual compiler fixture with a provisioned Kvik runtime:

```sh
python3 tools/test_task_effects_cli.py --runtime /path/to/kvik.kexe \
  --tools /path/to/tools.json --native-target macosArm64
```

Omit `--native-target` for JVM-only checks, or use `linuxX64` on a matching host.
The Linux host workflow runs the full JVM matrix. The macOS workflow uses
`--native-target macosArm64 --native-only` through the foreground process owner:
all Native cases plus a pure JVM baseline that checks target-key separation and
reuse of the JVM-built checker. This avoids repeating the full JVM matrix inside
the same bounded macOS invocation. Both targets verify warm
compilation reuse and restoration of the original cache key after source edits.
The fixture checks direct/transitive effects, permission boundaries, unknown calls,
initialization and recursive analysis, plus compilation reuse after unchanged and
restored inputs. It compiles the task functions without executing them.

The fixture uses `dependencies/project.json` and its checked-in dependency lock.
Its `metadata.json` maps locked metadata digests to the existing snapshots in
`tests/resolution/fixtures`; compiler JARs are copied
from the provisioned `compilerArtifacts` directory in the supplied tools file.
Compiler JARs must match both the dependency lock and `bootstrap/jvm-artifacts.lock.json`.
The common stdlib metadata JAR has a separate `dependencies/common-stdlib.json`
pin; if it is not provisioned, the fixture downloads it with bounded size/time,
digest verification and no redirects before starting offline verification.
The native CLI replays the dependency metadata with `check-lock --offline` before
compilation. This avoids a second compiler download through the Maven transport,
whose redirect policy differs from the pinned bootstrap downloader. The fixture
declares JVM, macosArm64 and linuxX64 so the same lock works on both CI hosts;
the requested compilation target still determines the executed graph.

To refresh dependencies, resolve a temporary copy of `dependencies/project.json`
with pins `org.jetbrains.kotlin:kotlin-stdlib:2.4.20` and
`org.jetbrains:annotations:13.0`. Retain the generated lock and its referenced
metadata blobs in `tests/resolution/fixtures`, update `dependencies/metadata.json`,
then review any pin changes.
Do not commit downloaded JARs.

This is a compiler-diagnostic experiment, not a hermeticity proof or an execution
sandbox. Unannotated entry points are outside its scope. Accepted standard-library
and JDK operations are trusted, and other compiler plugins or dependency changes
can alter behavior beyond this pass. Compiler acceptance does not change action
cache eligibility. The API is experimental and is not installed in Kvik's runtime
or bootstrap automatically.

Before integration into source build plugins, define input/output/tool handles
with resource identities and cache implications, add runtime access enforcement,
and choose the bootstrap stage that compiles and verifies the checker itself.
Task registration, generated manifests and capabilities for file/network/process
access remain follow-up research. An arbitrary annotation on external code must
not become an exemption from checking.
