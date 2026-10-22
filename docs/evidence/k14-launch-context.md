# K14 launch context for foreground recomposition

The source launcher exposes two bounded JSON queries for a foreground owner:

- `--print-composition` computes the desired composition digest from the selected
  sources, recipe, dependency pins, tool lock and host identity. It creates no
  Kvik cache, acquires no artifacts and starts no compiler. It works without
  installed compilers or an executable cache. This is a source observation, not
  an atomic snapshot or verification of an existing executable/tool installation.
- `--print-launch` uses ordinary verified executable selection, including an
  allowed rebuild when necessary, and returns the selected runtime plus its
  invocation-local bootstrap context. `--offline` and `--no-build` retain their
  normal meanings. No invalid or older cached composition becomes a fallback.

Normal launches pass the same context to the native executable in
`KVIK_BOOTSTRAP_LAUNCH`. The JSON is limited to 32 KiB and described by
`schemas/v1/bootstrap-launch.json`. It contains absolute source-root, Python,
launcher and runtime paths, the selected composition digest, and explicit
bootstrap arguments for tool/data/cache paths and offline/no-build policy.
Tool paths originally supplied through the launcher's supported environment
defaults are captured as explicit arguments. Relative paths are made absolute
before the original working directory is lost. Arguments remain an array;
consumers must invoke them directly, never through a shell.

This context belongs to the invocation and is not stored inside reusable binary
cache entries or added to the composition identity. It does not authenticate
external input or capture arbitrary environment variables/credentials. A future
Native consumer must validate its schema, supported argument structure and
composition binding before use. The current library foreground slice does not
yet consume this context; source-plugin recomposition/restart remains a separate
integration gate.

The intended controlled flow is: poll `--print-composition` in an owned child;
on a changed composition, drain existing work; run `--print-launch` in an owned
child; validate the returned descriptor; then replace the foreground coordinator
with the selected runtime. A failed rebuild must leave ordinary work disabled
for the obsolete composition. All hashing/building remains outside the control
loop. This document specifies that subsequent integration, not a passed gate.

The root `kvik` entry disables Python bytecode writes before importing bootstrap
modules, so source queries do not create `__pycache__` in distributed sources.
Queries reject accompanying runtime commands; print modes are mutually exclusive.

Validation:

```sh
.kvik/schema-tools/bin/python tests/bootstrap/test_launch_descriptor.py -v
python3 tests/bootstrap/test_identity.py -v
```

The descriptor suite exercises real subprocess source queries in a copied source
tree with absent caches/tools. File bytes/modes/mtimes stay unchanged, consumer-only
edits preserve the desired key, and a same-size/same-mtime plugin edit changes it.
An invalid current entry and an older entry cannot produce a descriptor under
`--no-build`. Unit interception of the final exec verifies exact descriptor/env
and argument forwarding, including spaces and shell-looking literal text; that
interception does not claim execution of a rebuilt native binary. The size bound
and query-command rejection are also exercised.

Five descriptor tests and seven identity tests passed. Independent review found
no confirmed defect and additionally checked environment-default paths and
mutually exclusive query modes.

An actual source build produced composition
`4b8122297516569f4a9bef5e121c373b6c8e321de1cf621e95af7f26f62d920d`.
Bootstrap reported 33,230 ms for Native construction. Its `--print-launch`
descriptor passed the schema; a repeated offline `--no-build --print-launch`
returned the same runtime/composition with the preserved policy. A normal
launcher invocation then executed `runtime-info` from that native binary and
reported the same composition. The observed descriptor and native response are
retained at `/private/tmp/kvik-launch-context-verified.json` and
`/private/tmp/kvik-launch-context-runtime-info.json`. This proves producer/cache
selection and native launch, not Native consumption of the descriptor or
foreground recomposition.
