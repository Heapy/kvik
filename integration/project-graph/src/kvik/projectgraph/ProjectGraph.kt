package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.native.*
import kvik.compiler.ksp.*
import kvik.application.applicationBundleAction

enum class ProjectMode { BUILD, TEST, APPLICATION }
data class JvmTests(val classes: List<String>, val engines: List<String> = listOf("junit-jupiter"))
data class ProjectGraphRequest(
    val modules: Set<String>,
    val targets: Set<String>,
    val mode: ProjectMode,
    val jvmTests: Map<String, JvmTests> = emptyMap(),
)
data class ProjectActionGraph(val nodes: List<ActionNode>, val requestedChecks: Set<String>, val terminalActions: Map<String, String>)

/** Host JVM producers may be prerequisites even when only a Native target was requested. */
fun projectToolTargets(project: ProjectSpec, request: ProjectGraphRequest): Set<String> {
    val modules = project.modules.associateBy { it.id }
    val visited = mutableSetOf<List<String>>()
    val targets = request.targets.toMutableSet()
    fun visit(module: String, target: String, scope: String, runtime: Boolean) {
        if (!visited.add(listOf(module, target, scope, runtime.toString()))) return
        targets += target
        val spec = modules.getValue(module)
        val dependencies = dependencyClosure(project, module, target, scope,
            if (runtime) DependencyUsage.RUNTIME else DependencyUsage.COMPILE)
        dependencies.modules.forEach { visit(it, target, "main", false) }
        if (scope == "test") visit(module, target, "main", false)
        spec.compilerPlugins.filter { it.module != null && target in it.targets && scope in it.scopes }.forEach {
            visit(it.module!!, "jvm", "main", true)
        }
    }
    for (module in request.modules) for (target in request.targets) {
        visit(module, target, "main", request.mode == ProjectMode.APPLICATION)
        if (request.mode == ProjectMode.TEST) visit(module, target, "test", true)
    }
    return targets.toSet()
}

/** Pure graph construction. The caller has verified the lock and owns all snapshot bytes in CAS. */
fun projectActionGraph(
    effective: EffectiveConfiguration,
    lock: DependencyLock,
    snapshot: TreeManifest,
    composition: Digest,
    toolchains: Map<String, Map<String, Digest>>,
    request: ProjectGraphRequest,
): ProjectActionGraph = Builder(effective, lock, snapshot, composition, toolchains, request).build()

private class Builder(
    effective: EffectiveConfiguration,
    lock: DependencyLock,
    snapshot: TreeManifest,
    private val composition: Digest,
    toolchains: Map<String, Map<String, Digest>>,
    request: ProjectGraphRequest,
) {
    private val project = parseProjectConfiguration(effective.document.objectValue().getValue("project")).project
    private val modules = project.modules.associateBy { it.id }
    private val files = TreeManifest(snapshot.files.toList()).files.sortedBy { it.path.value }
    private val tools = toolchains.mapValues { it.value.toMap() }
    private val request = request.copy(modules = request.modules.toSet(), targets = request.targets.toSet(),
        jvmTests = request.jvmTests.mapValues { it.value.copy(classes = it.value.classes.toList(), engines = it.value.engines.toList()) })
    private val selections = lock.selections.associateBy { it.request() }
    private val authorized = effective.document.objectValue().getValue("domains").arrayValue().map(ResolutionDomain::fromJson).toSet()
    private val nodes = linkedMapOf<String, ActionNode>()
    private val mainSources = mutableMapOf<Pair<String, String>, Sources>()

    init {
        require(lock.configuration == effective.digest) { "Stale dependency lock" }
        require(selections.size == lock.selections.size && lock.selections.map { it.request() } == effective.requests) { "Dependency lock request identities differ" }
        require(this.request.modules.isNotEmpty() && this.request.targets.isNotEmpty()) { "Select modules and targets explicitly" }
        require(this.request.modules.all { it in modules } && this.request.targets.all { it in setOf("jvm", "macosArm64") }) { "Unknown project module/target selection" }
        val expectedJvm = if (this.request.mode == ProjectMode.TEST && "jvm" in this.request.targets) this.request.modules else emptySet()
        require(this.request.jvmTests.keys == expectedJvm) { "Supply explicit JVM test selections for exactly the requested JVM test modules" }
        for (module in this.request.modules) for (target in this.request.targets) {
            target(module, target)
            require(tools[target]?.keys == if (target == "jvm") setOf("jdk", "jvm-compiler", "jvm-worker", "junit")
                else setOf("native", "jdk", "sdk", "native-dependencies")) { "Missing/incompatible tool identities: $target" }
            val scope = if (this.request.mode == ProjectMode.TEST) "test" else "main"
            if (scope == "test") require(target(module, target).test != null) { "No test compilation: $module:$target" }
            if (this.request.mode == ProjectMode.APPLICATION) require(target(module, target).application != null) { "No application settings: $module:$target" }
            val runtime = scope == "test" || this.request.mode == ProjectMode.APPLICATION
            val usages = if (target == "jvm") if (runtime) listOf("java-api", "java-runtime") else listOf("java-api") else if (runtime) listOf("kotlin-api", "kotlin-runtime") else listOf("kotlin-api")
            for (usage in usages) require(ResolutionDomain(module, target, scope, usage) in authorized) { "Lock configuration does not authorize $module:$target:$scope:$usage" }
        }
    }

    fun build(): ProjectActionGraph {
        val terminals = linkedMapOf<String, String>()
        for (module in request.modules.sorted()) for (target in request.targets.sorted()) {
            ensureMain(module, target)
            val check = "$module:$target:${request.mode.name.lowercase()}"
            terminals[check] = when (request.mode) {
                ProjectMode.BUILD -> mainId(module, target)
                ProjectMode.TEST -> ensureTest(module, target)
                ProjectMode.APPLICATION -> ensureApplication(module, target)
            }
        }
        return ProjectActionGraph(nodes.values.toList(), terminals.keys.toSet(), terminals.toMap())
    }

    private fun target(module: String, target: String) = modules.getValue(module).targets.singleOrNull { it.name == target }
        ?: error("Missing target $module:$target")
    private fun sourceSets(module: String, target: String, scope: String): List<SourceSet> {
        val spec = target(module, target)
        return sourceSetClosure(modules.getValue(module), if (scope == "main") listOf(spec.main)
            else listOf(spec.main, spec.test ?: error("Missing tests: $module:$target")))
    }
    private fun commonSets(module: String): Set<String> {
        val specification = modules.getValue(module)
        val reached = mutableMapOf<String, MutableSet<String>>()
        for (target in specification.targets) for (source in sourceSetClosure(specification, listOfNotNull(target.main, target.test)))
            reached.getOrPut(source.name) { mutableSetOf() } += target.name
        return reached.filter { (name, targets) -> targets.size > 1 || name in setOf("commonMain", "commonTest") }.keys
    }
    private data class Sources(val entries: List<FileEntry>, val paths: List<LogicalPath>, val common: List<LogicalPath>)
    private fun sources(module: String, target: String, scope: String): Sources {
        val shared = commonSets(module)
        val selected = linkedMapOf<String, Pair<FileEntry, Boolean>>()
        for (set in sourceSets(module, target, scope)) for (directory in set.directories) {
            require(files.none { it.path == directory }) { "Source directory is a file: ${directory.value}" }
            for (file in files.filter { it.path.value.startsWith(directory.value + "/") }) {
                require(file.path.value.endsWith(".kt")) { "Unsupported source file: ${file.path.value}; declare resources separately" }
                require(file.path.value !in selected) { "Overlapping source ownership: ${file.path.value}" }
                selected[file.path.value] = file.copy(path = LogicalPath("src/${file.path.value}")) to (set.name in shared)
            }
        }
        val values = selected.values.sortedBy { it.first.path.value }
        return Sources(values.map { it.first }, values.map { it.first.path }, values.filter { it.second }.map { it.first.path })
    }
    private fun resources(module: String, target: String, scope: String): List<FileEntry> {
        val main = sourceSets(module, target, "main").map { it.name }.toSet()
        val sets = sourceSets(module, target, scope).filter { scope == "main" || it.name !in main }
        val selected = linkedMapOf<String, FileEntry>()
        for (set in sets) for (directory in set.resources) {
            require(files.none { it.path == directory }) { "Resource directory is a file: ${directory.value}" }
            for (file in files.filter { it.path.value.startsWith(directory.value + "/") }) {
                val relative = file.path.value.removePrefix(directory.value + "/")
                require(relative !in selected) { "Ambiguous resource overlay: $module:$scope:$relative" }
                selected[relative] = file.copy(path = LogicalPath("runtime/$module/$scope/$relative"))
            }
        }
        return selected.values.sortedBy { it.path.value }
    }
    private fun closure(module: String, target: String, scope: String, runtime: Boolean = false): DependencyClosure =
        dependencyClosure(project, module, target, scope, if (runtime) DependencyUsage.RUNTIME else DependencyUsage.COMPILE)

    private fun external(module: String, target: String, scope: String, usage: String): List<LockedArtifact> {
        val domain = ResolutionDomain(module, target, scope, usage)
        val roots = closure(module, target, scope, usage.endsWith("runtime")).external
        val consumer = if (target == "jvm") jvmTargetFromOptions(target(module, target).optionsFor(scope)) else null
        return roots.flatMap { locked(it, domain, consumer).artifacts }.distinctBy { artifactPath(it, false) }
    }
    private fun locked(coordinate: Coordinate, domain: ResolutionDomain, consumerJvm: Int? = null): ScopedSelection {
        val variant = if (domain.isHostTool) jvmVariantRequest("java-runtime", COMPILER_PLUGIN_JVM_HOST)
            else if (domain.target == "jvm") jvmVariantRequest(domain.usage, consumerJvm ?: error("Missing JVM consumer version"))
            else VariantRequest(domain.target, "kotlin-api", emptyMap())
        return selections[ResolutionRequest(coordinate, variant, domain)] ?: error("Missing locked dependency: $coordinate:$domain")
    }
    private fun artifactPath(artifact: LockedArtifact, plugin: Boolean): LogicalPath {
        val extension = artifact.url.substringAfterLast('.', "")
        require(extension in setOf("jar", "klib")) { "Unsupported locked artifact: ${artifact.url}" }
        return LogicalPath("${if (plugin) "plugins" else "deps"}/${artifact.digest.hash}-${artifact.digest.sizeBytes}.$extension")
    }
    private fun artifactEntries(artifacts: List<LockedArtifact>, target: String, plugin: Boolean = false): List<FileEntry> = artifacts.map {
        require(it.url.endsWith(if (target == "jvm" || plugin) ".jar" else ".klib")) { "Wrong artifact kind for $target: ${it.url}" }
        FileEntry(artifactPath(it, plugin), it.digest)
    }.distinct()
    private fun selectedPlugins(module: String, target: String, scope: String): List<CompilerPluginSpec> =
        modules.getValue(module).compilerPlugins.filter { target in it.targets &&
            (scope in it.scopes || it.module == null && target == "macosArm64" && scope == "test" && "main" in it.scopes) }.distinct()
            .also { require(it.size <= 1) { "Multiple compiler plugins per compilation are unsupported" } }
    private fun plugins(module: String, target: String, scope: String): List<FileEntry> {
        val selected = selectedPlugins(module, target, scope).filter { it.module == null }
        require(selected.all { it.options.isEmpty() && it.inputs.isEmpty() }) { "Serialization plugin options/extra inputs are unsupported" }
        val roots = selected.map { it.coordinate!! }.distinct()
        require(roots.size <= 1) { "Multiple compiler plugins are unsupported" }
        return roots.flatMap { coordinate ->
            require(coordinate == Coordinate("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.20")) { "Only pinned serialization compiler plugin 2.4.20 is supported" }
            val artifacts = locked(coordinate, ResolutionDomain(module, target, scope, "java-runtime", "compiler-plugin")).artifacts
            require(artifacts.size == 1 && artifacts.single().coordinate == coordinate) { "Unsupported serialization plugin closure/pin" }
            artifactEntries(artifacts, "jvm", true)
        }
    }
    private fun pluginModules(module: String, target: String, scope: String): List<String> =
        selectedPlugins(module, target, scope).flatMap { plugin -> plugin.module?.let {
            listOf(it) + closure(it, "jvm", "main", true).modules
        } ?: emptyList() }.distinct()

    private data class WorkspacePlugin(val entries: List<FileEntry>, val applications: List<JvmPluginApplication>)
    private fun workspacePlugin(module: String, target: String, scope: String, results: Map<String, ActionResult>): WorkspacePlugin {
        val plugin = selectedPlugins(module, target, scope).singleOrNull()?.takeIf { it.module != null }
            ?: return WorkspacePlugin(emptyList(), emptyList())
        val prefix = "plugins/${plugin.id!!}"
        val entries = mutableListOf<FileEntry>()
        val classpath = mutableListOf<LogicalPath>()
        for (producer in pluginModules(module, target, scope)) {
            val root = LogicalPath("$prefix/modules/$producer/classes")
            classpath += root
            val original = modulePath(producer, "jvm", "main").value
            entries += remap(results, producer, "jvm", "main").map { it.copy(path = LogicalPath(root.value + it.path.value.removePrefix(original))) }
            val resource = resources(producer, "jvm", "main")
            if (resource.isNotEmpty()) {
                val resourceRoot = LogicalPath("$prefix/modules/$producer/resources")
                classpath += resourceRoot
                entries += resource.map { it.copy(path = LogicalPath(resourceRoot.value + "/" + it.path.value.removePrefix("runtime/$producer/main/"))) }
            }
        }
        val domain = ResolutionDomain(module, target, scope, "java-runtime", "compiler-plugin")
        val artifacts = closure(plugin.module!!, "jvm", "main", true).external.flatMap { locked(it, domain).artifacts }.distinctBy { artifactPath(it, true) }
        require(artifacts.none { it.coordinate.group == "org.jetbrains.kotlin" && it.coordinate.name in setOf("kotlin-compiler", "kotlin-compiler-embeddable") &&
            (it.coordinate.name != "kotlin-compiler-embeddable" || it.coordinate.version != plugin.compilerVersion) }) { "Workspace plugin compiler API differs from the pinned embeddable compiler" }
        for (artifact in artifactEntries(artifacts, "jvm", true)) {
            val entry = artifact.copy(path = LogicalPath("$prefix/deps/${artifact.path.value.substringAfterLast('/')}"))
            entries += entry; classpath += entry.path
        }
        fun input(path: LogicalPath) = LogicalPath("plugin-inputs/${plugin.id}/${path.value}")
        fun output(path: LogicalPath) = LogicalPath("plugins/${plugin.id}/${path.value}")
        entries += plugin.inputs.map { path -> (files.singleOrNull { it.path == path } ?: error("Missing declared plugin input: ${path.value}")).copy(path = input(path)) }
        val application = JvmPluginApplication(plugin.id, plugin.compilerVersion!!, classpath, plugin.options, plugin.inputs.map(::input),
            plugin.pathOptions.map { it.copy(path = if (it.kind == PluginPathKind.INPUT_FILE) input(it.path) else output(it.path)) }, plugin.outputs.map(::output))
        return WorkspacePlugin(entries, listOf(application))
    }
    private fun nativeTools(applications: List<JvmPluginApplication>): Map<String, Digest> {
        val native = tools.getValue("macosArm64")
        if (applications.isEmpty()) return native
        val jvm = tools["jvm"] ?: error("Native workspace compiler plugins require JVM producer/worker tool identities")
        require(jvm.keys == setOf("jdk", "jvm-compiler", "jvm-worker", "junit") && jvm["jdk"] == native["jdk"]) {
            "Native workspace compiler plugins require matching verified JVM host tools"
        }
        return native + jvm.filterKeys { it != "jdk" }
    }

    private fun merge(vararg groups: List<FileEntry>): TreeManifest {
        val entries = linkedMapOf<LogicalPath, FileEntry>()
        for (file in groups.flatMap { it }) {
            require(entries[file.path] == null || entries[file.path] == file) { "Conflicting input: ${file.path.value}" }
            entries[file.path] = file
        }
        return TreeManifest(entries.values.sortedBy { it.path.value })
    }
    private fun mainId(module: String, target: String) = "$module:$target:main"
    private fun compileId(module: String, target: String) = "$module:$target:test-compile"
    private fun linkId(module: String) = "$module:macosArm64:test-link"
    private fun nativeTestLibraryPath(module: String) = LogicalPath("modules/$module/macosArm64/test.klib")
    private fun generationId(module: String) = "$module:macosArm64:ksp-test"
    private fun modulePath(module: String, target: String, scope: String) = LogicalPath("modules/$module/$target/$scope" + if (target == "jvm") "" else if (scope == "main") ".klib" else "/tests.kexe")
    private fun remap(results: Map<String, ActionResult>, module: String, target: String, scope: String): List<FileEntry> {
        val id = if (scope == "main") mainId(module, target) else if (target == "macosArm64") linkId(module) else compileId(module, target)
        val result = results[id] ?: error("Missing prerequisite result: $id")
        require(result.actionId == id && result.successful) { "Unsuccessful/mismatched prerequisite: $id" }
        val output = if (target == "jvm") "classes" else if (scope == "main") "module.klib" else "tests.kexe"
        require(result.outputs.files.isNotEmpty()) { "Empty prerequisite outputs: $id" }
        val prefix = modulePath(module, target, scope).value
        if (target == "macosArm64" && scope == "main") {
            require(result.outputs.files.count { it.path.value == output } == 1 &&
                result.outputs.files.all { it.path.value == output || it.path.value.startsWith("plugins/") }) { "Unexpected Native library output" }
        } else require(result.outputs.files.all { (it.path.value.startsWith("$output/") || it.path.value.startsWith("plugins/")) && target == "jvm" ||
            target == "macosArm64" && (it.path.value == output || it.path.value.startsWith("$output.dSYM/")) }) { "Unexpected prerequisite output: $id" }
        val selected = result.outputs.files.filter { if (target == "jvm") it.path.value.startsWith("$output/")
            else it.path.value == output || it.path.value.startsWith("$output.dSYM/") }
        require(selected.isNotEmpty()) { "Missing compiled classes: $id" }
        return selected.map { it.copy(path = LogicalPath(prefix + it.path.value.removePrefix(output))) }
    }
    private fun validateResults(results: Map<String, ActionResult>, dependencies: Set<String>) {
        require(results.keys == dependencies && results.all { (id, result) -> result.actionId == id && result.successful }) { "Prerequisite result set/status differs" }
    }
    private fun options(module: String, target: String, scope: String, sources: Sources): CompilerOptions = compilerOptions(module, target, target(module, target).optionsFor(scope)).also {
        require(!it.multiplatform || sources.common.isNotEmpty()) { "Explicit multiplatform option requires common fragments" }
    }

    private fun ensureMain(module: String, target: String) {
        val id = mainId(module, target)
        if (id in nodes) return
        val dependencies = closure(module, target, "main").modules
        dependencies.forEach { ensureMain(it, target) }
        val pluginDependencies = pluginModules(module, target, "main")
        pluginDependencies.forEach { ensureMain(it, "jvm") }
        val source = sources(module, target, "main")
        require(source.paths.isNotEmpty()) { "No Kotlin main sources: $module:$target" }
        mainSources[module to target] = source
        val options = options(module, target, "main", source)
        val resource = resources(module, target, "main")
        val artifacts = artifactEntries(external(module, target, "main", if (target == "jvm") "java-api" else "kotlin-api"), target)
        val plugin = plugins(module, target, "main")
        require(plugin.none { p -> artifacts.any { it.digest == p.digest } }) { "Compiler plugin also appears in consumer classpath" }
        val edges = dependencies.map { mainId(it, target) }.toSet() + pluginDependencies.map { mainId(it, "jvm") }
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            val dependencyFiles = dependencies.flatMap { remap(results, it, target, "main") }
            val workspace = workspacePlugin(module, target, "main", results)
            val inputs = merge(source.entries, resource, artifacts, plugin, dependencyFiles, workspace.entries)
            val classpath = dependencies.map { modulePath(it, target, "main") } + artifacts.map { it.path }
            if (target == "jvm") jvmCompileAction(JvmCompilation(module, source.paths, classpath, LogicalPath("classes"), plugin.map { it.path }, source.common,
                jvmTarget = options.jvmTarget, optIns = options.optIns, explicitApi = options.explicitApi, warningsAsErrors = options.warningsAsErrors,
                jdkRelease = options.jdkRelease, pluginApplications = workspace.applications), inputs, composition, tools.getValue(target))
            else nativeCompileAction(NativeCompilation(module, source.paths, source.common, classpath, plugin.map { it.path }, LogicalPath("module.klib"),
                moduleName = options.nativeModuleName, optIns = options.optIns, explicitApi = options.explicitApi, warningsAsErrors = options.warningsAsErrors,
                pluginApplications = workspace.applications), inputs, composition, nativeTools(workspace.applications),
                timeoutMillis = target(module, target).nativeTimeouts?.compileMillis ?: 180_000)
        }
    }

    private fun ensureApplication(module: String, target: String): String {
        val application = target(module, target).application ?: error("No application settings: $module:$target")
        val runtimeDependencies = closure(module, target, "main", true).modules
        runtimeDependencies.forEach { ensureMain(it, target) }
        val runtimeIds = listOf(module) + runtimeDependencies
        val artifacts = artifactEntries(external(module, target, "main", if (target == "jvm") "java-runtime" else "kotlin-runtime"), target)
        val resource = runtimeIds.flatMap { resources(it, target, "main") }
        val id = "$module:$target:application"
        if (target == "jvm") {
            val edges = runtimeIds.map { mainId(it, target) }.toSet()
            nodes[id] = ActionNode(id, edges) { results ->
                validateResults(results, edges)
                val classes = runtimeIds.flatMap { remap(results, it, target, "main") }
                val classpath = runtimeIds.flatMap { producer -> listOfNotNull(modulePath(producer, target, "main"),
                    LogicalPath("runtime/$producer/main").takeIf { root -> resource.any { it.path.value.startsWith(root.value + "/") } }) } + artifacts.map { it.path }
                applicationBundleAction(ApplicationLaunch(module, target, application, classpath),
                    merge(classes, resource, artifacts), composition, tools.getValue(target))
            }
        } else {
            val linkId = "$module:$target:application-link"
            val linkEdges = runtimeIds.map { mainId(it, target) }.toSet()
            val options = options(module, target, "main", mainSources.getValue(module to target))
            nodes[linkId] = ActionNode(linkId, linkEdges) { results ->
                validateResults(results, linkEdges)
                val libraries = runtimeIds.flatMap { remap(results, it, target, "main") }
                nativeCompileAction(NativeCompilation(module, emptyList(), libraries = runtimeDependencies.map { modulePath(it, target, "main") } + artifacts.map { it.path },
                    output = LogicalPath("application.kexe"), produce = NativeOutput.PROGRAM, moduleName = options.nativeModuleName,
                    warningsAsErrors = options.warningsAsErrors, includedLibrary = modulePath(module, target, "main"), entryPoint = application.entryPoint),
                    merge(libraries, artifacts), composition, tools.getValue(target), timeoutMillis = target(module, target).nativeTimeouts?.linkMillis ?: 180_000)
            }
            nodes[id] = ActionNode(id, setOf(linkId)) { results ->
                validateResults(results, setOf(linkId))
                val linked = results.getValue(linkId).outputs.files
                require(linked.any { it.path.value == "application.kexe" && it.executable } && linked.all {
                    it.path.value == "application.kexe" || it.path.value.startsWith("application.kexe.dSYM/")
                }) { "Unexpected Native application output" }
                val prefix = "modules/$module/$target/"
                val executable = LogicalPath(prefix + "application.kexe")
                val program = linked.map { it.copy(path = LogicalPath(prefix + it.path.value)) }
                applicationBundleAction(ApplicationLaunch(module, target, application, executable = executable),
                    merge(program, resource), composition, tools.getValue(target))
            }
        }
        return id
    }

    private fun ensureTest(module: String, target: String): String {
        val dependencies = closure(module, target, "test").modules
        val runtimeDependencies = closure(module, target, "test", true).modules
        (dependencies + runtimeDependencies).distinct().forEach { ensureMain(it, target) }
        val main = mainSources.getValue(module to target)
        val allSources = sources(module, target, "test")
        val source = if (target == "macosArm64") allSources else allSources.let { all ->
            Sources(all.entries.filter { it.path !in main.paths }, all.paths.filter { it !in main.paths }, all.common.filter { it !in main.paths })
        }
        val resource = resources(module, target, "test") + resources(module, target, "main")
        val moduleIds = listOf(module) + dependencies
        val kotest = target(module, target).nativeTestSettings
        val pluginDependencies = pluginModules(module, target, "test")
        pluginDependencies.forEach { ensureMain(it, "jvm") }
        val producerEdges = moduleIds.map { mainId(it, target) }.toSet() + pluginDependencies.map { mainId(it, "jvm") }
        val compileEdges = producerEdges + if (kotest == null) emptySet() else setOf(generationId(module))
        val hasTestCompile = target == "macosArm64" || source.paths.isNotEmpty()
        if (hasTestCompile) {
            val options = options(module, target, "test", source)
            if (target == "macosArm64") {
                val production = compilerOptions(module, target, target(module, target).compilerOptions)
                require(options.optIns.containsAll(production.optIns)) { "Native tests recompile main sources: test opt-ins must include main opt-ins" }
            }
            val artifacts = artifactEntries(external(module, target, "test", if (target == "jvm") "java-api" else "kotlin-api"), target)
            if (kotest != null) {
                val lockedRuntime = external(module, target, "test", "kotlin-api")
                require(lockedRuntime.any { it.coordinate == Coordinate("io.kotest", "kotest-framework-engine-macosarm64", "6.2.5") } &&
                    lockedRuntime.none { it.coordinate.group == "io.kotest" && it.coordinate.version != "6.2.5" }) { "Native Kotest tests require the pinned 6.2.5 runtime closure" }
                fun host(purpose: String, roots: List<Coordinate>): List<FileEntry> {
                    val domain = ResolutionDomain(module, target, "test", "java-runtime", purpose)
                    require(domain in authorized)
                    return artifactEntries(roots.flatMap { locked(it, domain).artifacts }, "jvm").distinctBy { it.path }
                        .map { it.copy(path = LogicalPath("ksp/$purpose/${it.path.value.substringAfterLast('/')}")) }
                }
                val ksp = host("ksp-runtime", listOf("symbol-processing-api", "symbol-processing-aa-embeddable", "symbol-processing-common-deps").map { Coordinate("com.google.devtools.ksp", it, kotest.kspVersion) })
                val processors = host("symbol-processor", listOf(kotest.processor))
                val testSources = source.entries.filter { it.path !in main.paths }
                require(testSources.isNotEmpty()) { "Native Kotest requires test Kotlin sources" }
                nodes[generationId(module)] = ActionNode(generationId(module), producerEdges) { results ->
                    validateResults(results, producerEdges)
                    val modules = moduleIds.flatMap { remap(results, it, target, "main") }
                    kspNativeAction(KspNativeCompilation(module, options.nativeModuleName, testSources.map { it.path }, source.common.filter { path -> testSources.any { it.path == path } },
                        moduleIds.map { modulePath(it, target, "main") } + artifacts.map { it.path }, ksp.map { it.path }, processors.map { it.path }),
                        merge(testSources, modules, artifacts, ksp, processors), composition, tools.getValue(target))
                }
            }
            val plugin = plugins(module, target, "test")
            require(plugin.none { p -> artifacts.any { it.digest == p.digest } }) { "Compiler plugin also appears in consumer classpath" }
            nodes[compileId(module, target)] = ActionNode(compileId(module, target), compileEdges) { results ->
                validateResults(results, compileEdges)
                val linkedModules = if (target == "jvm") moduleIds else dependencies
                val dependencyFiles = linkedModules.flatMap { remap(results, it, target, "main") }
                val workspace = workspacePlugin(module, target, "test", results)
                val generated = if (kotest == null) emptyList() else results.getValue(generationId(module)).outputs.files.also {
                    require(it.map { file -> file.path } == listOf(kotestGeneratedLauncher)) { "KSP prerequisite must contain exactly the raw pinned launcher" }
                }
                val inputs = merge(source.entries, resource, artifacts, plugin, dependencyFiles, workspace.entries, generated)
                val classpath = linkedModules.map { modulePath(it, target, "main") } + artifacts.map { it.path }
                if (target == "jvm") jvmCompileAction(JvmCompilation(module, source.paths, classpath, LogicalPath("classes"), plugin.map { it.path }, source.common,
                    friendPaths = listOf(modulePath(module, target, "main")), jvmTarget = options.jvmTarget, optIns = options.optIns,
                    explicitApi = options.explicitApi, warningsAsErrors = options.warningsAsErrors, jdkRelease = options.jdkRelease, pluginApplications = workspace.applications), inputs, composition, tools.getValue(target))
                else nativeCompileAction(NativeCompilation(module, source.paths + generated.map { it.path }, source.common, classpath, plugin.map { it.path }, LogicalPath("tests.klib"),
                    NativeOutput.LIBRARY, options.nativeModuleName, options.optIns, options.explicitApi, options.warningsAsErrors,
                    kotestLauncher = kotest?.let { kotestGeneratedLauncher }, pluginApplications = workspace.applications), inputs, composition, nativeTools(workspace.applications),
                    timeoutMillis = target(module, target).nativeTimeouts?.compileMillis ?: 180_000)
            }
            if (target == "macosArm64") {
                val linkEdges = runtimeDependencies.map { mainId(it, target) }.toSet() + compileId(module, target)
                val runtimeArtifacts = artifactEntries(external(module, target, "test", "kotlin-runtime"), target)
                nodes[linkId(module)] = ActionNode(linkId(module), linkEdges) { results ->
                    validateResults(results, linkEdges)
                    val compiled = results.getValue(compileId(module, target)).outputs.files
                    require(compiled.count { it.path == LogicalPath("tests.klib") } == 1 &&
                        compiled.all { it.path == LogicalPath("tests.klib") || it.path.value.startsWith("plugins/") }) { "Native test compilation must produce its klib and declared plugin outputs" }
                    val included = compiled.single { it.path == LogicalPath("tests.klib") }.copy(path = nativeTestLibraryPath(module))
                    val dependencyFiles = runtimeDependencies.flatMap { remap(results, it, target, "main") }
                    val inputs = merge(listOf(included), runtimeArtifacts, dependencyFiles)
                    nativeCompileAction(NativeCompilation(module, emptyList(), libraries = runtimeDependencies.map { modulePath(it, target, "main") } + runtimeArtifacts.map { it.path },
                        output = LogicalPath("tests.kexe"), produce = if (kotest == null) NativeOutput.TEST else NativeOutput.KOTEST_TEST,
                        moduleName = options.nativeModuleName, warningsAsErrors = options.warningsAsErrors, includedLibrary = included.path), inputs, composition, tools.getValue(target),
                        timeoutMillis = target(module, target).nativeTimeouts?.linkMillis ?: 180_000)
                }
            }
        } else require(selectedPlugins(module, target, "test").isEmpty()) { "Test compiler plugin selected without test compilation sources" }
        val id = "$module:$target:test"
        val runtimeIds = listOf(module) + runtimeDependencies
        val edges = runtimeIds.map { mainId(it, target) }.toSet() + if (hasTestCompile) setOf(if (target == "macosArm64") linkId(module) else compileId(module, target)) else emptySet()
        val allResources = resource + runtimeDependencies.flatMap { resources(it, target, "main") }
        val runtime = if (target == "jvm") artifactEntries(external(module, target, "test", "java-runtime"), target) else emptyList()
        val testSelection = request.jvmTests[module]
        if (target == "jvm") JvmTestSelection(module, listOf(LogicalPath("validated-placeholder")), testSelection!!.classes, testSelection.engines)
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            val testFiles = if (hasTestCompile) remap(results, module, target, "test") else emptyList()
            if (target == "jvm") {
                val dependencyFiles = runtimeIds.flatMap { remap(results, it, target, "main") }
                val inputs = merge(testFiles, dependencyFiles, allResources, runtime)
                fun resourceRoot(id: String, scope: String) = LogicalPath("runtime/$id/$scope").takeIf { prefix -> allResources.any { it.path.value.startsWith(prefix.value + "/") } }
                val classpath = listOfNotNull(if (hasTestCompile) modulePath(module, target, "test") else null, resourceRoot(module, "test")) +
                    runtimeIds.flatMap { listOfNotNull(modulePath(it, target, "main"), resourceRoot(it, "main")) } + runtime.map { it.path }
                jvmTestAction(JvmTestSelection(module, classpath, testSelection!!.classes, testSelection.engines), inputs, composition, tools.getValue(target))
            } else if (kotest != null) kotestNativeTestAction(module, modulePath(module, target, "test"), merge(testFiles, allResources), composition, tools.getValue(target))
            else nativeTestAction(module, modulePath(module, target, "test"), merge(testFiles, allResources), composition, tools.getValue(target))
        }
        return id
    }
}
