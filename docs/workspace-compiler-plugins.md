# Workspace compiler plugins

A module can reference a compiler plugin implemented by another module in the
same project. Kvik builds the plugin and its prerequisites before compiling the
consumer; publishing a plugin JAR to a repository is unnecessary.

Add a declaration to the consumer module's `compilerPlugins` array:

```json
{
  "module": "myCompilerPlugin",
  "id": "example.plugin",
  "compilerVersion": "2.4.20",
  "targets": ["jvm", "macosArm64"],
  "scopes": ["main", "test"],
  "options": ["mode=strict"],
  "inputs": ["config/plugin.txt"],
  "pathOptions": [
    {"name": "config", "kind": "input-file", "path": "config/plugin.txt"},
    {"name": "report", "kind": "output-directory", "path": "report"}
  ],
  "outputs": ["report"]
}
```

The option names belong to the plugin. Declare only targets and test compilations
that exist on the consumer. The producer needs a JVM target even when all its
consumers are Native. Declare its compiler API and helper dependencies normally;
Kvik preserves the producer's compile and runtime dependency visibility. Include
the K2 registrar and optional command-line processor service descriptors in the
producer's resources. The plugin ID must match the declaration, and the plugin
must support the selected target and pinned compiler version.

`targets` and `scopes` select the compilations that load a workspace plugin.
Native test compilation currently recompiles the module's main and test sources
together. Select both `main` and `test` when the transformation is required in
both the production library and test executable. A `main`-only workspace plugin
is not implicitly loaded by the test compilation.

Plugin classes, runtime helpers and resources are isolated from the consumer's
ordinary dependency classpath. A Native build with a workspace plugin also
requires the configured JVM tools to build the producer and run the compiler
adapter. It uses the Native compiler API as the plugin's parent loader and checks
the explicitly declared JVM compiler API dependency against the pinned artifacts.
This supports compatible K2 plugins; a plugin that assumes JVM-only backend APIs
still needs a Native implementation.

Literal options retain their order and values, including commas, equals signs and line breaks.
Use `pathOptions` for files and output directories so Kvik can materialize paths
inside each compilation's own workspace. Declared inputs and plugin implementation
bytes participate in compilation caching. Changing either rebuilds affected
consumers. Generated reports are retained with the compilation outputs and are
excluded from consumer library linking and runtime classpaths.

After changing configuration, resolve the lock explicitly, then build or test:

```sh
./kvik --no-build -- resolve --project /absolute/project/kvik.json
./kvik --offline --no-build -- test --project /absolute/project/kvik.json \
  --tools /absolute/project/tools.json --module app --target macosArm64
```

The current adapter accepts one workspace compiler plugin per compilation and
Kotlin 2.4.20 on JVM or macosArm64. Project scripts can use workspace plugins with
`targets: ["jvm"]` and `scopes: ["script"]`; see the
[script contract](decisions/k12-scripts.md).
