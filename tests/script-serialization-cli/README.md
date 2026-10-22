# Published serialization script acceptance preparation

This is a source-only fourth phase for `tools/test_scripts_cli.py`. It has not run
against a composed runtime at this preparation freeze. It does not claim all K12
acceptance gates complete.

Integration contract for the harness owner:

- Import `tools/script_acceptance_published.py` as a sibling module, add phase
  `published`, and keep standalone selector `--file Main.kt` with script name
  `plain` and companion `Main.kvik.json`.
- Call `prepare(project)` after creating the project but **before** capturing its
  initial inventory, validating its configuration, or writing invocation evidence.
- Set `gate.policy = policy()` and construct `gate.common` with that policy, just
  as for existing named scripts. These are explicit stdlib 2.4.20 and annotations
  13.0 pins, with no plugin substitution.
- Receipt-bind the module bytes and the complete `tests/script-serialization-cli`
  inventory at invocation and verify them again at finish; the existing Gate
  receipt only covers its original two fixture directories.
- Dispatch `run(gate)`. It calls the existing schema/journal/action/worker-request
  audit plus additional full ordered classpath, input-binding, generated-output,
  metadata/CAS and independent artifact-reference assertions. It returns
  `gate.finish(...)` evidence.

The phase resolves actual Central dependencies, then runs cold and warm with
`--offline`. Both must produce different UUID effects. The compile action must be
executed cold, reused warm, and invalidated independently by a primary source edit
and helper source edit. The program directly calls `Payload.serializer()`, round
trips a data class, checks its serial name, and emits exact transformed values.
Removing the plugin must fail compilation without a new effect.

All three lock selections have exact artifact sets and bytes: script API and
runtime each contain serialization JSON JVM/core JVM 1.11.0, stdlib 2.4.20 and
annotations 13.0; compiler-plugin runtime contains only the pinned serialization
plugin 2.4.20. `artifacts.json` copies independently retained JVM reference bytes
from the two named repository locks. No jars are copied into the fixture or placed
on classpaths by the harness. Actual resolver outputs must match those references.
Consumer JVM release/target is 11; plugin host metadata request is 25. The full
ordered runtime classpath also includes the executor's separately verified
implicit language stdlib after declared dependencies, as required by the current
script contract.

Published plugin options and versions other than 2.4.20 are deliberately
unsupported by `ScriptGraph.pluginInputs`. The phase tests explicit graph refusal
of nonempty options and an actually resolved 2.3.20 plugin, with no compiler worker
request or program effect. It does **not** claim successful published plugin-option
invalidation or universal binary plugin compatibility. Workspace-plugin option
invalidation is a separate named-script gate. Each configuration edit first proves offline stale-lock refusal without writes,
then explicitly resolves normally and checks the updated lock offline. Online
resolution may update CAS; artifact identities remain independently checked.
The final restored valid lock must equal its original bytes and reuse the successful changed-source compile while
executing the program afresh.

This phase does not prove an explicit compiler-identity mutation, concurrent
worktrees, or script integration with continuous application sessions. Those gates
remain separate. A preparation-only schema/static check must not be represented
as successful runtime execution.
