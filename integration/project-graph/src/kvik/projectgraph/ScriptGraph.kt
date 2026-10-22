package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*

data class ScriptGraphRequest(val name: String, val arguments: List<String> = emptyList())

/** Shares real module prerequisites and locked artifacts; scripts never masquerade as modules. */
fun scriptActionGraph(effective: EffectiveConfiguration, lock: DependencyLock, snapshot: TreeManifest,
                      composition: Digest, toolchains: Map<String, Digest>, request: ScriptGraphRequest): ProjectActionGraph =
    ScriptGraphBuilder(effective, lock, snapshot, composition, toolchains, request).build()

private class ScriptGraphBuilder(
    private val effective: EffectiveConfiguration,
    lock: DependencyLock,
    snapshot: TreeManifest,
    private val composition: Digest,
    toolchains: Map<String, Digest>,
    request: ScriptGraphRequest,
) {
    private val authoring = effective.document.objectValue()
    private val standalone = authoring["standaloneScript"]?.let(::parseStandaloneScriptConfiguration)
    private val project = authoring["project"]?.let(::parseProjectConfiguration)?.project
        ?: standalone?.let { ProjectSpec(it.name, it.repositories, emptyList()) } ?: error("Missing script authoring document")
    private val request = request.copy(arguments = request.arguments.toList())
    private val script = standalone?.also { require(it.name == request.name) { "Unknown standalone script: ${request.name}" } }?.expanded()
        ?: expandScript(project, request.name)
    private val files = TreeManifest(snapshot.files.toList()).files.sortedBy { it.path.value }
    private val tools = toolchains.toMap()
    private val selections = lock.selections.associateBy { it.request() }
    private val authorized = authoring.getValue("domains").arrayValue().map(ResolutionDomain::fromJson).toSet()
    private val options = compilerOptions("script-${script.name}", "jvm", script.compilerOptions.map { it.value })
    private val plugins = script.compilerPlugins.map { it.value }
    private val compileClosure = scriptDependencyClosure(project, script, DependencyUsage.COMPILE)
    private val runtimeClosure = scriptDependencyClosure(project, script, DependencyUsage.RUNTIME)
    private val pluginModules = plugins.singleOrNull()?.module?.let {
        listOf(it) + dependencyClosure(project, it, "jvm", "main", DependencyUsage.RUNTIME).modules
    } ?: emptyList()
    private val moduleGraph: ProjectActionGraph

    init {
        require(("project" in authoring) != ("standaloneScript" in authoring)) { "Ambiguous script authoring document" }
        require(lock.configuration == effective.digest && selections.size == lock.selections.size && lock.selections.map { it.request() } == effective.requests) { "Stale or mismatched script lock" }
        require(tools.keys == setOf("jdk", "jvm-compiler", "jvm-worker", "junit")) { "Missing/incompatible JVM tools" }
        require(!options.multiplatform) { "Script sources do not declare common fragments" }
        require(plugins.size <= 1) { "Multiple compiler plugins per script are unsupported" }
        for (usage in listOf("java-api", "java-runtime")) require(ResolutionDomain.script(script.name, usage) in authorized) { "Unauthorized script dependency scope: $usage" }
        if (plugins.isNotEmpty()) require(ResolutionDomain.script(script.name, "java-runtime", "compiler-plugin") in authorized) { "Unauthorized script plugin scope" }
        val prerequisites = (compileClosure.modules + runtimeClosure.modules + pluginModules).toSet()
        moduleGraph = if (prerequisites.isEmpty()) ProjectActionGraph(emptyList(), emptySet(), emptyMap()) else {
            require(standalone == null) { "Standalone script cannot reference modules" }
            projectActionGraph(effective, lock, snapshot, composition, mapOf("jvm" to tools), ProjectGraphRequest(prerequisites, setOf("jvm"), ProjectMode.BUILD))
        }
    }

    private fun moduleId(name: String) = "$name:jvm:main"
    private fun moduleRoot(name: String) = LogicalPath("modules/$name/jvm/main")
    private fun artifactPath(value: LockedArtifact, prefix: String): LogicalPath {
        require(value.url.endsWith(".jar")) { "Non-JVM artifact in script: ${value.url}" }
        return LogicalPath("$prefix/${value.digest.hash}-${value.digest.sizeBytes}.jar")
    }
    private fun artifacts(roots: List<Coordinate>, usage: String, plugin: Boolean = false): List<LockedArtifact> {
        val domain = ResolutionDomain.script(script.name, usage, if (plugin) "compiler-plugin" else "dependencies")
        val variant = jvmVariantRequest(usage, if (plugin) COMPILER_PLUGIN_JVM_HOST else options.jvmTarget)
        return roots.flatMap { selections[ResolutionRequest(it, variant, domain)]?.artifacts ?: error("Missing script artifact: $it:$domain") }
            .distinctBy { artifactPath(it, "deps") }
    }
    private fun entries(artifacts: List<LockedArtifact>, prefix: String = "deps") = artifacts.map { FileEntry(artifactPath(it, prefix), it.digest) }
    private fun merge(vararg groups: List<FileEntry>): TreeManifest {
        val selected = linkedMapOf<LogicalPath, FileEntry>()
        for (file in groups.flatMap { it }) {
            require(selected[file.path] == null || selected[file.path] == file) { "Conflicting script input: ${file.path.value}" }
            selected[file.path] = file
        }
        return TreeManifest(selected.values.sortedBy { it.path.value })
    }
    private fun selectedRoots(roots: List<LogicalPath>, sources: Boolean): List<FileEntry> {
        val result = linkedMapOf<LogicalPath, FileEntry>()
        for (root in roots) {
            val matched = files.filter { it.path == root || it.path.value.startsWith(root.value + "/") }
            // Empty resource directories have no entries; source roots must contain Kotlin source.
            if (sources) require(matched.isNotEmpty()) { "Empty or missing script source: ${root.value}" }
            for (file in matched) {
                if (sources) require(file.path.value.endsWith(".kt")) { "Unsupported script source: ${file.path.value}" }
                val relative = if (file.path == root) file.path.value.substringAfterLast('/') else file.path.value.removePrefix(root.value + "/")
                val path = LogicalPath(if (sources) "src/${file.path.value}" else "scripts/${script.name}/resources/$relative")
                require(path !in result) { "Ambiguous script resource/source overlay: ${path.value}" }
                result[path] = file.copy(path = path)
            }
        }
        return result.values.sortedBy { it.path.value }
    }
    private fun moduleResources(name: String): List<FileEntry> {
        val module = project.modules.single { it.id == name }
        val main = module.targets.single { it.name == "jvm" }.main
        val selected = linkedMapOf<LogicalPath, FileEntry>()
        for (source in sourceSetClosure(module, listOf(main))) for (root in source.resources) {
            require(files.none { it.path == root }) { "Module resource root is a file: ${root.value}" }
            for (file in files.filter { it.path.value.startsWith(root.value + "/") }) {
                val path = LogicalPath("runtime/$name/main/" + file.path.value.removePrefix(root.value + "/"))
                require(path !in selected) { "Ambiguous module resource overlay: ${path.value}" }
                selected[path] = file.copy(path = path)
            }
        }
        return selected.values.sortedBy { it.path.value }
    }
    private fun compiled(results: Map<String, ActionResult>, id: String, root: LogicalPath): List<FileEntry> {
        val result = results[id] ?: error("Missing script prerequisite: $id")
        require(result.actionId == id && result.successful) { "Unsuccessful/mismatched script prerequisite: $id" }
        require(result.outputs.files.all { it.path.value.startsWith("classes/") || it.path.value.startsWith("plugins/") }) { "Unexpected JVM prerequisite outputs: $id" }
        val classes = result.outputs.files.filter { it.path.value.startsWith("classes/") }
        require(classes.isNotEmpty()) { "Missing prerequisite classes: $id" }
        return classes.map { it.copy(path = LogicalPath(root.value + "/" + it.path.value.removePrefix("classes/"))) }
    }
    private fun validate(results: Map<String, ActionResult>, edges: Set<String>) {
        require(results.keys == edges && results.all { (id, result) -> result.actionId == id && result.successful }) { "Script prerequisite result set/status differs" }
    }
    private data class PluginInputs(val files: List<FileEntry>, val published: List<LogicalPath>, val applications: List<JvmPluginApplication>)
    private fun pluginInputs(results: Map<String, ActionResult>): PluginInputs {
        val plugin = plugins.singleOrNull() ?: return PluginInputs(emptyList(), emptyList(), emptyList())
        plugin.coordinate?.let { coordinate ->
            require(coordinate == Coordinate("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.20") && plugin.options.isEmpty() && plugin.inputs.isEmpty()) { "Unsupported published script compiler plugin" }
            val locked = artifacts(listOf(coordinate), "java-runtime", true)
            require(locked.size == 1 && locked.single().coordinate == coordinate) { "Unsupported serialization plugin closure/pin" }
            val files = entries(locked, "plugins")
            return PluginInputs(files, files.map { it.path }, emptyList())
        }
        val prefix = "plugins/${plugin.id!!}"
        val files = mutableListOf<FileEntry>()
        val classpath = mutableListOf<LogicalPath>()
        for (module in pluginModules) {
            val root = LogicalPath("$prefix/modules/$module/classes")
            classpath += root
            files += compiled(results, moduleId(module), root)
            val resources = moduleResources(module)
            if (resources.isNotEmpty()) {
                val resourceRoot = LogicalPath("$prefix/modules/$module/resources")
                classpath += resourceRoot
                files += resources.map { it.copy(path = LogicalPath(resourceRoot.value + "/" + it.path.value.removePrefix("runtime/$module/main/"))) }
            }
        }
        val runtime = dependencyClosure(project, plugin.module!!, "jvm", "main", DependencyUsage.RUNTIME)
        val artifacts = artifacts(runtime.external, "java-runtime", true)
        require(artifacts.none { it.coordinate.group == "org.jetbrains.kotlin" && it.coordinate.name in setOf("kotlin-compiler", "kotlin-compiler-embeddable") &&
            (it.coordinate.name != "kotlin-compiler-embeddable" || it.coordinate.version != plugin.compilerVersion) }) { "Workspace plugin compiler API differs from pinned compiler" }
        for (entry in entries(artifacts, "$prefix/deps")) { files += entry; classpath += entry.path }
        fun input(path: LogicalPath) = LogicalPath("plugin-inputs/${plugin.id}/${path.value}")
        fun output(path: LogicalPath) = LogicalPath("plugins/${plugin.id}/${path.value}")
        files += plugin.inputs.map { path -> (this.files.singleOrNull { it.path == path } ?: error("Missing script plugin input: ${path.value}")).copy(path = input(path)) }
        val application = JvmPluginApplication(plugin.id, plugin.compilerVersion!!, classpath, plugin.options, plugin.inputs.map(::input),
            plugin.pathOptions.map { it.copy(path = if (it.kind == PluginPathKind.INPUT_FILE) input(it.path) else output(it.path)) }, plugin.outputs.map(::output))
        return PluginInputs(files, emptyList(), listOf(application))
    }

    fun build(): ProjectActionGraph {
        val sources = selectedRoots(script.declaration.sources, true)
        val resources = selectedRoots(script.declaration.resources, false)
        val compileArtifacts = entries(artifacts(compileClosure.external, "java-api"))
        val runtimeArtifacts = entries(artifacts(runtimeClosure.external, "java-runtime"))
        val compileId = "script:${script.name}:jvm:compile"
        val runId = "script:${script.name}:jvm:run"
        val compileEdges = (compileClosure.modules + pluginModules).map(::moduleId).toSet()
        val compile = ActionNode(compileId, compileEdges) { results ->
            validate(results, compileEdges)
            val modules = compileClosure.modules.flatMap { compiled(results, moduleId(it), moduleRoot(it)) }
            val plugin = pluginInputs(results)
            require(plugin.published.none { path -> plugin.files.single { it.path == path }.digest in compileArtifacts.map { it.digest } }) { "Compiler plugin appears in script compile classpath" }
            jvmCompileAction(JvmCompilation("script-${script.name}", sources.map { it.path }, compileClosure.modules.map(::moduleRoot) + compileArtifacts.map { it.path },
                LogicalPath("classes"), plugin.published, jvmTarget = options.jvmTarget, optIns = options.optIns, explicitApi = options.explicitApi,
                warningsAsErrors = options.warningsAsErrors, jdkRelease = options.jdkRelease, pluginApplications = plugin.applications, includeStandardLibrary = true),
                merge(sources, resources, compileArtifacts, modules, plugin.files), composition, tools)
        }
        val runEdges = runtimeClosure.modules.map(::moduleId).toSet() + compileId
        val run = ActionNode(runId, runEdges) { results ->
            validate(results, runEdges)
            val root = LogicalPath("scripts/${script.name}/classes")
            val classes = compiled(results, compileId, root) + runtimeClosure.modules.flatMap { compiled(results, moduleId(it), moduleRoot(it)) }
            val moduleResources = runtimeClosure.modules.flatMap(::moduleResources)
            val classpath = listOf(root) + listOfNotNull(LogicalPath("scripts/${script.name}/resources").takeIf { resources.isNotEmpty() }) +
                runtimeClosure.modules.flatMap { module -> listOfNotNull(moduleRoot(module), LogicalPath("runtime/$module/main").takeIf { p -> moduleResources.any { it.path.value.startsWith(p.value + "/") } }) } + runtimeArtifacts.map { it.path }
            jvmScriptAction(JvmScriptLaunch(script.name, root, classpath, script.declaration.mainClass, request.arguments),
                merge(classes, resources, moduleResources, runtimeArtifacts), composition, tools, script.declaration.environment, script.declaration.timeoutMillis)
        }
        return ProjectActionGraph(moduleGraph.nodes + compile + run, setOf(runId), mapOf(runId to runId))
    }
}
