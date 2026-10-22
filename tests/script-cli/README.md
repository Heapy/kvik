# Public script acceptance

`tools/test_scripts_cli.py` invokes the actual composed Native CLI. It never builds
Kvik, invokes Gradle/KTC, replaces the resolver, or launches the JVM compiler itself.
Consumer compilation is requested only through `run-script`.

The independently retained phases are:

* `standalone`: a `.kt` file with its explicit companion, no declared dependency,
  implicit pinned stdlib, cold compilation and warm compilation with fresh effects,
  exact literal argv/environment/cwd/EOF and UTF-8/NUL streams, ordinary exits
  125/127/130/255, discovery/compile errors, explicit main selection, read-only
  inspection/check/query and no-write selector/alias/policy refusals.
* `named`: explicit catalog/profile dependencies, workspace library with a private
  runtime dependency, and unchanged tracked K13 compiler-plugin sources. Ordered
  script and plugin classpaths are compared with the complete selected lock
  closures. Plugin reports/effects, source/resource/input/profile/catalog changes,
  local hits, private-import rejection, selected freshness and expansion origins
  are checked.
* `lifecycle`: actual Java child/grandchild processes with slow shutdown hooks,
  timeout, INT/TERM cancellation and abrupt CLI owner loss. Retained process tables
  capture owner, guardian, descendants and process group; terminal checks require
  all captured processes and every live group member gone in the first process
  table after ordinary CLI terminal. Only SIGKILL owner-loss allows bounded
  eventual cleanup, because that owner cannot acknowledge completion. No guardian SIGKILL,
  escaped process sessions, uninterruptible kernel tasks or arbitrary foreign
  process ownership are claimed.

* `published`: real standalone serialization compiler plugin and generated serializer,
  pinned external runtime artifacts, offline checks/runs, source/helper changes,
  missing-plugin compile failure and explicit unsupported-options/version refusals.
* `named-invalidation`: successful workspace-plugin option changes with exact reports,
  helper-source recompilation through producer and script, unaffected module cache hits,
  ordinary dependency body changes with fixed config/lock and changed output bytes,
  and final warm compilation with fresh execution.
* `concurrency`: a fixture-only local Git seed with two detached worktrees, one shared
  CAS and separate locks/runs/effects. Both actual process trees must coexist; after
  cancelling one, its first terminal table must be drained while the second exact
  tree is alive. A project-local release file permits the survivor to complete
  normally, then each worktree executes fresh using the same cached compile key.
  Git commands/revision are retained; prepare-only invokes no Git processes.
* `compiler-identity`: an independently source-built alternate runtime swaps only
  the ordered compiler distribution entries; A cold/warm, B cold/warm, A reuse
  runs share one project/CAS. Both runtimes are bound before execution and at final
  publication. See `tests/compiler-identity/README.md` for required source/build
  provenance and the explicit composition-confounding limitation.

Each invocation creates a new phase attempt, including with `--case`. Failed
attempts and their commands remain; this is not a mid-phase resume mechanism.
No phase success is inferred from an earlier attempt. The same case requires the
same runtime/guardian/manifest/lock and tool document. The invoked file must be
the resolved manifest-bound `kvik.kexe`. The input tool document must
reference the provisioned pinned installations and compiler artifacts. Actual
Native provider verification remains authoritative for installed tool contents.

```sh
python -B tools/test_scripts_cli.py --prepare-only --tools /absolute/tools.json
python -B tools/test_scripts_cli.py --runtime /absolute/kvik.kexe --tools /absolute/tools.json --phase standalone
python -B tools/test_scripts_cli.py --runtime /absolute/kvik.kexe --tools /absolute/tools.json --case /retained/case --phase named
python -B tools/test_scripts_cli.py --runtime /absolute/kvik.kexe --tools /absolute/tools.json --case /retained/case --phase lifecycle
python -B -m unittest discover -s tests/script-cli -p 'test_*.py' -v
```

Prepare-only and Python harness checks do not prove consumer compilation or
execution. A completed actual phase has a `summary.json`; failed phases retain
`failure.json`. Exact runtime hashes, commands, controlled invocation environment,
source inventories, stdout/stderr, verification journals/CAS output hashes and
ownership evidence are retained alongside each phase. The final summary binds
the complete phase file inventory (excluding the summary itself), including all
requests, observations, action specs, CAS files and process-table evidence.
Harness timeouts retain partial binary streams and a `harness-timeout` receipt
with no invented program exit code; emergency cleanup is recorded separately
and cannot satisfy an ordinary lifecycle assertion.

The original three phases passed against composition `d87e052a...`; the extension
phases require the refreshed runtime containing the specific published-plugin
refusal diagnostics. Adding their source is not a claim that they have run.
Every extension module and its fixture tree is bound before preparation and checked
again before final evidence publication. Child worktree evidence is included in
the parent phase inventory. Shared-cache concurrency tests use only fixture-owned
repositories and signal only captured owners; there is no remote Git transport.

Actual `--phase compiler-identity` and `--phase all` require `--alternate-runtime`.
A single phase such as `--phase published` does not. Alternate identity is bound in
case metadata when supplied, so later attempts cannot silently substitute runtime B.
