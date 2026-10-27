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
The Linux host workflow runs the JVM subset.
The fixture checks direct/transitive effects, permission boundaries, unknown calls,
initialization and recursive analysis, plus compilation reuse after unchanged and
restored inputs. It compiles the task functions without executing them.

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
