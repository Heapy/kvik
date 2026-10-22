# K14 source graph launcher integration

The launcher accepts `--source-lock` and `--source-cache`, uses the invoking
bootstrap's implementation, and compiles explicit verified graph inputs. The
local default retains runtime lock v1. Graph compositions use v2 with the full
observed graph, including source pins, registry order, Native artifact closure,
metadata and provided-library bindings. Physical checkout/cache paths remain
outside the composition identity.

The implementation connects the reviewed source store to existing installed-tool
verification, owned compiler execution, JVM worker construction and atomic
runtime publication. Native Kotlin and worker inputs come from snapshot roles;
external KLIBs use the declared order. Fixed C/interop recipes use the selected
runtime package root. Registry initialization checks each plugin's ID/API.

`--print-composition` does not acquire inputs or create caches. A missing runtime
with `--no-build` fails before source acquisition. Offline builds require cached
remote bytes. Source/cache overlap checks run before creating output directories.
The launch descriptor captures source lock/cache paths for foreground replay.

Graph runtimes retain three manifest-digested receipts:

- `source-acquisition.json`: current acquisition or freshly verified cache facts.
- `source-inputs.json`: logical paths, hashes, executable bits and input roles.
- `source-commands.jsonl`: exact argv, constructed environment and timeout for
  each owned build command. These physical paths are execution evidence only.

All three receipts are checked on runtime cache hits. The launcher re-observes
the input lock, local source content and trusted bootstrap recipe before
publishing a compiled composition. A failed compiler or changed observation
does not publish that attempt.

## Focused verification

Run from the integration checkout:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tests/bootstrap/test_source_launcher.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tests/contracts/test_source_graph_schema.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/check_schemas.py
python3 -B tests/bootstrap/test_identity.py
```

The source launcher suite passed 15 groups in 2.824 seconds. It includes legacy
launch-descriptor tests, schema-valid full graph identity, remote declarations
with absent local runtime sources, no query writes/acquisition, content and
metadata invalidation, unchanged whitespace/consumer edits, source/cache overlap
and symlink rejection, and retained receipt corruption checks. Its publication
boundary test uses real observation, local provisioning and snapshot copying,
with installed-tool checks and compiler work stubbed: source edits, input-lock
edits and compiler failure cannot publish; the unchanged case publishes a
synthetic runtime. This proves orchestration, not compiler compatibility.

Eight source-graph schema groups, eleven schema examples/canonical vectors, and
seven legacy identity checks also passed. Independently rerunning the foreground
contract suite passed all nine Native launch/resume tests; retained evidence is
`/private/tmp/kvik-k14-foreground-source-flags/.kvik/tests/foreground-bootstrap/case-kngmleds`.

## Remaining acceptance

This slice does not claim full K14 completion. Actual remote full-runtime
bootstrap with two plugins, shared helper and real serialization KLIB closure,
production consumer smoke, relocated/offline executable reuse and rebuild,
runtime invalidation/concurrent publication, foreground source graph handoff,
and shared executable cache integration remain explicit acceptance work.
