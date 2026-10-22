# Workspace JVM compiler-plugin contract

The additive project fields are `id`, `compilerVersion`, `pathOptions` and
`outputs`. Workspace applications require an explicit ID, Kotlin `2.4.20`,
JVM target and main/test scopes. Published serialization declarations retain
their existing shape. Script and Native workspace applications are rejected.

Literal `options` preserve ordered `name=value` entries, including repeated
names and values containing `=`. `PluginPathOption(name, kind, path)` binds a
declared input file or output directory through `input-file` or
`output-directory`. Every declared output requires a binding in this slice;
ancillary inputs may be declared without a path-valued option. Outputs
are unique nonoverlapping logical directory roots. Physical path substitution
belongs to the worker; user strings are never shell commands.

`JvmPluginApplication(id, compilerVersion, classpath, options, inputs,
pathOptions, outputs)` is the shared action/worker descriptor. All paths are
logical to the containing action or worker workspace. `WorkerRequest` appends
`pluginApplications = emptyList()`; absent/empty applications preserve legacy
serialization. At most one application is supported and the legacy plugin
classpath cannot be combined with an application. Unknown fields fail parsing.

This commit freezes contracts only. Runtime loading, graph prerequisites,
isolated output publication and complete JVM fixture verification follow in
the same implementation branch. It does not establish a passed K13 gate.
