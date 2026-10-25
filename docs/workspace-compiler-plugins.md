# Compiler plugins from modules and Maven

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
consumers target Native, JS or Wasm. Declare its compiler API and helper dependencies normally;
Kvik preserves the producer's compile and runtime dependency visibility. Include
the K2 registrar and optional command-line processor service descriptors in the
producer's resources. The plugin ID must match the declaration, and the plugin
must support the selected target and pinned compiler version.

`targets` and `scopes` select the compilations that load a workspace plugin.
Native test compilation currently recompiles the module's main and test sources
together. Select both `main` and `test` when the transformation is required in
both the production library and test executable. A `main`-only workspace plugin
is not implicitly loaded by Native test compilation. JS/Wasm test compilation
also recompiles production sources and automatically inherits main-scoped plugins.
Main and test plugins can be combined when their IDs and output roots are distinct.

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

A compilation accepts up to 16 explicitly described compiler plugins with distinct IDs and
Kotlin 2.4.20, on JVM, macosArm64, `js` and `wasmJs`. Each plugin uses an isolated
classpath; explicit plugins are installed in declaration order. Shared producer
dependencies are built once, and each plugin keeps its own options and outputs.
A compilation can also apply the published
`org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20`
plugin alongside them. Declare the entries in `compilerPlugins`; each retains its
own target and scope selection. Common publication and documentation metadata
apply each plugin only when its declaration covers every target of that metadata
compilation. A workspace plugin cannot reuse the serialization plugin ID when both
are selected.
JS/Wasm uses the JVM-hosted compiler and requires a plugin compatible with that
backend. Source-free JS/Wasm links do not load the plugin. Project scripts can use workspace plugins with
`targets: ["jvm"]` and `scopes: ["script"]`; see the
[script contract](decisions/k12-scripts.md).

Published plugins use the same `id`, `compilerVersion`, `options`, `inputs`,
`pathOptions` and `outputs` contract. Replace `module` with a pinned Maven
`coordinate`, for example:

```json
{
  "coordinate": "org.jetbrains.kotlin:kotlin-allopen-compiler-plugin-embeddable:2.4.20",
  "id": "org.jetbrains.kotlin.allopen",
  "compilerVersion": "2.4.20",
  "targets": ["jvm"],
  "scopes": ["main", "test"],
  "options": ["annotation=example.Open"],
  "inputs": []
}
```

Resolve explicitly after changing the declaration. The lock pins the plugin and
its JVM runtime dependency closure separately from consumer dependencies. Plugin
JARs are materialized in an isolated classpath and loaded only by the owned compiler
worker. Their contents, options and declared inputs participate in compilation
caching. Explicit published plugins can be mixed with workspace plugins; the
16-plugin limit applies to their combined count. The existing serialization
declaration without `id`/`compilerVersion` remains a supported shorthand.

Use `targets: ["jvm"]` and `scopes: ["script"]` in a script declaration or shared
dependency profile. Both project scripts and standalone `.kt`/`.kts` scripts use
the same locked plugin mechanism. A plugin must expose exactly one K2 registrar,
at most one command-line processor and the declared plugin ID, and must be
compatible with Kotlin 2.4.20 and the selected backend. JVM-only plugins do not
gain Native/JS/Wasm compatibility by being published. Explicit Native plugin
declarations select `main` and `test` independently, as workspace plugins do.

Each explicitly described plugin has its own resolution domain, identified by
`pluginId` in the lock and dependency queries. Two plugins may use different
versions of the same helper library. Workspace plugin compilation still resolves
the producer module's own dependencies normally; its consumer-side loading
classpath uses the plugin's domain.

To pin a helper version for one plugin, append its ID to `--scope-pin`:

```sh
--scope-pin 'app:jvm:main:java-runtime:compiler-plugin:example.plugin=example:helper:2'
--scope-pin 'script:maintenance:jvm:script:java-runtime:compiler-plugin:example.plugin=example:helper:2'
```

Use the same pin when resolving and building. Global `--pin` values still apply
across domains. Existing locks for explicitly described plugins need an explicit
resolve after this domain change; update their plugin-specific pins to include
the ID. The serialization shorthand keeps its existing domain without `pluginId`.
