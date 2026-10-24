package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.native.*
import kvik.compiler.ksp.*
import kvik.application.applicationBundleAction
import kvik.library.*
import kvik.abi.*

enum class ProjectMode { BUILD, TEST, APPLICATION, PACKAGE, ABI_DUMP, ABI_CHECK }
private fun ProjectMode.checkScope() = name.lowercase().replace('_', '-')
data class JvmCoverageTools(val version: String, val agent: Digest, val reporter: Digest, val excludes: List<String> = emptyList()) {
    fun toJson(): JsonValue.Object = obj("version" to version.json(), "agent" to agent.toJson(), "reporter" to reporter.toJson(),
        "excludes" to excludes.map { it.json() }.json())
}
data class JvmTests(val classes: List<String>, val engines: List<String> = listOf("junit-jupiter"), val coverage: JvmCoverageTools? = null)
data class ProjectGraphRequest(
    val modules: Set<String>,
    val targets: Set<String>,
    val mode: ProjectMode,
    val jvmTests: Map<String, JvmTests> = emptyMap(),
)
data class ProjectActionGraph(val nodes: List<ActionNode>, val requestedChecks: Set<String>, val terminalActions: Map<String, String>)

fun projectRequestedChecks(request: ProjectGraphRequest): Set<String> =
    request.modules.flatMap { module -> request.targets.map { target -> "$module:$target:${request.mode.checkScope()}" } +
        if (request.mode == ProjectMode.PACKAGE && request.targets.size > 1) listOf("$module:common:package") else emptyList()
    }.toSet()

/** Host JVM producers may be prerequisites even when only a Native target was requested. */
fun projectToolTargets(project: ProjectSpec, request: ProjectGraphRequest): Set<String> {
    val modules = project.modules.associateBy { it.id }
    val visited = mutableSetOf<List<String>>()
    val targets = request.targets.toMutableSet()
    if (request.mode in setOf(ProjectMode.ABI_DUMP, ProjectMode.ABI_CHECK) ||
        request.mode == ProjectMode.PACKAGE && request.targets.size > 1) targets += "jvm"
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
        visit(module, target, "main", request.mode in setOf(ProjectMode.APPLICATION, ProjectMode.PACKAGE) ||
            request.mode == ProjectMode.BUILD && modules.getValue(module).targets.single { it.name == target }.nativeFramework != null)
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
    private val policy = effective.policy
    private val project = parseProjectConfiguration(effective.document.objectValue().getValue("project")).project
    private val modules = project.modules.associateBy { it.id }
    private val files = TreeManifest(snapshot.files.toList()).files.sortedBy { it.path.value }
    private val tools = toolchains.mapValues { it.value.toMap() }
    private val request = request.copy(modules = request.modules.toSet(), targets = request.targets.toSet(),
        jvmTests = request.jvmTests.mapValues { it.value.copy(classes = it.value.classes.toList(), engines = it.value.engines.toList(), coverage = it.value.coverage?.let { coverage -> coverage.copy(excludes = coverage.excludes.toList()) }) })
    private val selections = lock.selections.associateBy { it.request() }
    private val authorized = effective.document.objectValue().getValue("domains").arrayValue().map(ResolutionDomain::fromJson).toSet()
    private val nodes = linkedMapOf<String, ActionNode>()
    private val mainSources = mutableMapOf<Pair<String, String>, Sources>()
    private val nativeMainNames = mutableMapOf<Pair<String, String>, String>()
    private val rootPublicationModules: Set<String> = if (request.mode == ProjectMode.PACKAGE &&
        request.targets.size > 1) buildSet {
        fun visit(module: String) {
            if (!add(module)) return
            commonPublicationSourceSets(modules.getValue(module)).flatMap { it.dependencies }
                .filterIsInstance<Dependency.Module>().filter { it.scope == DependencyScopeKind.COMPILE }.forEach { visit(it.module) }
        }
        request.modules.forEach(::visit)
    } else emptySet()

    init {
        require(lock.configuration == effective.digest) { "Stale dependency lock" }
        require(selections.size == lock.selections.size && lock.selections.map { it.request() } == effective.requests) { "Dependency lock request identities differ" }
        require(this.request.modules.isNotEmpty() && this.request.targets.isNotEmpty()) { "Select modules and targets explicitly" }
        require(this.request.modules.all { it in modules } && this.request.targets.all { it == "jvm" || NativeTarget.find(it) != null }) { "Unknown project module/target selection" }
        val expectedJvm = if (this.request.mode == ProjectMode.TEST && "jvm" in this.request.targets) this.request.modules else emptySet()
        require(this.request.jvmTests.keys == expectedJvm) { "Supply explicit JVM test selections for exactly the requested JVM test modules" }
        for (module in this.request.modules) for (target in this.request.targets) {
            target(module, target)
            require(tools[target]?.keys == if (target == "jvm") setOf("jdk", "jvm-compiler", "jvm-worker", "junit")
                else NativeTarget.require(target).toolIdentityNames) { "Missing/incompatible tool identities: $target" }
            val scope = if (this.request.mode == ProjectMode.TEST) "test" else "main"
            if (scope == "test") require(target(module, target).test != null) { "No test compilation: $module:$target" }
            if (this.request.mode == ProjectMode.APPLICATION) require(target(module, target).application != null) { "No application settings: $module:$target" }
            val runtime = scope == "test" || this.request.mode in setOf(ProjectMode.APPLICATION, ProjectMode.PACKAGE) ||
                this.request.mode == ProjectMode.BUILD && target(module, target).nativeFramework != null
            val usages = if (target == "jvm") if (runtime) listOf("java-api", "java-runtime") else listOf("java-api") else if (runtime) listOf("kotlin-api", "kotlin-runtime") else listOf("kotlin-api")
            for (usage in usages) require(ResolutionDomain(module, target, scope, usage) in authorized) { "Lock configuration does not authorize $module:$target:$scope:$usage" }
        }
    }

    fun build(): ProjectActionGraph {
        val terminals = linkedMapOf<String, String>()
        for (module in request.modules.sorted()) for (target in request.targets.sorted()) {
            ensureMain(module, target)
            val check = "$module:$target:${request.mode.checkScope()}"
            terminals[check] = when (request.mode) {
                ProjectMode.BUILD -> if (target(module, target).nativeFramework == null) mainId(module, target) else ensureFramework(module, target)
                ProjectMode.TEST -> ensureTest(module, target)
                ProjectMode.APPLICATION -> ensureApplication(module, target)
                ProjectMode.PACKAGE -> ensurePackage(module, target)
                ProjectMode.ABI_DUMP -> ensureAbiDump(module, target)
                ProjectMode.ABI_CHECK -> ensureAbiCheck(module, target)
            }
        }
        if (request.mode == ProjectMode.PACKAGE && request.targets.size > 1) {
            for (module in request.modules.sorted()) {
                val root = ensureRootPackage(module)
                terminals["$module:common:package"] = root
            }
        }
        return ProjectActionGraph(nodes.values.toList(), terminals.keys.toSet(), terminals.toMap())
    }

    private fun ensureAbiDump(module: String, target: String): String {
        val id = "$module:$target:abi-dump"
        if (id in nodes) return id
        require(tools["jvm"]?.keys == setOf("jdk", "jvm-compiler", "jvm-worker", "junit")) { "ABI extraction requires JVM tool identities" }
        val dependencies = setOf(mainId(module, target))
        nodes[id] = ActionNode(id, dependencies) { results ->
            validateResults(results, dependencies)
            abiDumpAction(AbiExtraction(module, target, modulePath(module, target, "main")),
                TreeManifest(remap(results, module, target, "main")), composition, tools.getValue("jvm"))
        }
        return id
    }

    private fun ensureAbiCheck(module: String, target: String): String {
        val id = "$module:$target:abi-check"
        val baseline = target(module, target).abi?.baseline ?: error("ABI check requires a baseline: $module:$target")
        val entry = files.singleOrNull { it.path == baseline } ?: error("Missing ABI baseline: ${baseline.value}")
        require(!entry.executable && entry.digest.sizeBytes <= ABI_MAX_BYTES) { "Invalid ABI baseline: ${baseline.value}" }
        val dump = ensureAbiDump(module, target)
        nodes[id] = ActionNode(id, setOf(dump)) { results ->
            validateResults(results, setOf(dump))
            val output = results.getValue(dump).outputs
            require(output.files.map { it.path.value }.toSet() == setOf("abi.json", "api.txt") && output.files.none { it.executable }) { "Invalid ABI dump output" }
            val comparison = AbiComparison(module, target, LogicalPath("candidate/abi.json"), LogicalPath("baseline/api.txt"))
            abiCheckAction(comparison, TreeManifest(listOf(output.files.single { it.path.value == "abi.json" }.copy(path = comparison.snapshot),
                entry.copy(path = comparison.baseline))), composition)
        }
        return id
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
            (scope in it.scopes || it.module == null && NativeTarget.find(target) != null && scope == "test" && "main" in it.scopes) }.distinct()
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
    private fun nativeTools(target: String, applications: List<JvmPluginApplication>): Map<String, Digest> {
        val native = tools.getValue(target)
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
    private fun linkId(module: String, target: String) = "$module:$target:test-link"
    private fun nativeTestLibraryPath(module: String, target: String) = LogicalPath("modules/$module/$target/test.klib")
    private fun generationId(module: String, target: String) = "$module:$target:ksp-test"
    private fun modulePath(module: String, target: String, scope: String) = LogicalPath("modules/$module/$target/$scope" + if (target == "jvm") "" else if (scope == "main") ".klib" else "/tests.kexe")
    private fun remap(results: Map<String, ActionResult>, module: String, target: String, scope: String): List<FileEntry> {
        val id = if (scope == "main") mainId(module, target) else if (NativeTarget.find(target) != null) linkId(module, target) else compileId(module, target)
        val result = results[id] ?: error("Missing prerequisite result: $id")
        require(result.actionId == id && result.successful) { "Unsuccessful/mismatched prerequisite: $id" }
        val output = if (target == "jvm") "classes" else if (scope == "main") "module.klib" else "tests.kexe"
        require(result.outputs.files.isNotEmpty()) { "Empty prerequisite outputs: $id" }
        val prefix = modulePath(module, target, scope).value
        if (NativeTarget.find(target) != null && scope == "main") {
            require(result.outputs.files.count { it.path.value == output } == 1 &&
                result.outputs.files.all { it.path.value == output || it.path.value.startsWith("plugins/") }) { "Unexpected Native library output" }
        } else require(result.outputs.files.all { (it.path.value.startsWith("$output/") || it.path.value.startsWith("plugins/")) && target == "jvm" ||
            NativeTarget.find(target) != null && (it.path.value == output || target == NativeTarget.MACOS_ARM64.projectName && it.path.value.startsWith("$output.dSYM/")) }) { "Unexpected prerequisite output: $id" }
        val selected = result.outputs.files.filter { if (target == "jvm") it.path.value.startsWith("$output/")
            else it.path.value == output || target == NativeTarget.MACOS_ARM64.projectName && it.path.value.startsWith("$output.dSYM/") }
        require(selected.isNotEmpty()) { "Missing compiled classes: $id" }
        return selected.map { it.copy(path = LogicalPath(prefix + it.path.value.removePrefix(output))) }
    }
    private fun validateResults(results: Map<String, ActionResult>, dependencies: Set<String>) {
        require(results.keys == dependencies && results.all { (id, result) -> result.actionId == id && result.successful }) { "Prerequisite result set/status differs" }
    }
    private fun compilerModuleName(module: String, target: String): String =
        if (NativeTarget.find(target) != null) modules.getValue(module).publication?.coordinate?.let { "${it.group}:${it.name}" } ?: module else module
    private fun options(module: String, target: String, scope: String, sources: Sources): CompilerOptions = compilerOptions(compilerModuleName(module, target), target, target(module, target).optionsFor(scope)).also {
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
        if (NativeTarget.find(target) != null) {
            val prior = nativeMainNames[target to options.nativeModuleName]
            require(prior == null || prior == module || modules.getValue(prior).publication == null && modules.getValue(module).publication == null) {
                "Published Native libraries have conflicting module names: $prior and $module use ${options.nativeModuleName}"
            }
            nativeMainNames[target to options.nativeModuleName] = module
        }
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
                pluginApplications = workspace.applications, target = NativeTarget.require(target)), inputs, composition, nativeTools(target, workspace.applications),
                timeoutMillis = target(module, target).nativeTimeouts?.compileMillis ?: 180_000)
        }
    }

    private fun publicationDependencies(module: String, target: String): List<LibraryDependency> {
        val declared = sourceSets(module, target, "main").flatMap { it.dependencies }
        require(NativeTarget.find(target) == null || declared.none { it.scope == DependencyScopeKind.RUNTIME }) {
            "Native library publication cannot represent runtime-only dependencies for Kotlin/Native consumers: $module; declare a compile dependency or remove the runtime-only dependency before packaging"
        }
        val usages = if (target == "jvm") listOf("java-api", "java-runtime") else listOf("kotlin-api", "kotlin-runtime")
        for (usage in usages) {
            val domain = ResolutionDomain(module, target, "main", usage)
            require(domain in authorized) { "Packaging requires locked main API and runtime domains: $domain" }
            require(policy.exclusionsFor(domain).isEmpty()) { "Library publication cannot encode metadata exclusions: $domain" }
            val roots = closure(module, target, "main", usage.endsWith("runtime")).external
            val consumer = if (target == "jvm") jvmTargetFromOptions(target(module, target).compilerOptions) else null
            val artifacts = roots.flatMap { locked(it, domain, consumer).artifacts }
            for (pin in policy.pinsFor(domain)) {
                fun sameComponent(value: Coordinate) = value.group == pin.group && value.name == pin.name
                val matchingRoots = roots.filter(::sameComponent)
                val matchingArtifacts = artifacts.filter { sameComponent(it.coordinate) }
                if (matchingRoots.isEmpty() && matchingArtifacts.isEmpty()) continue
                require(matchingRoots.isNotEmpty() && matchingRoots.all { it.version == pin.version } &&
                    matchingArtifacts.all { it.coordinate.version == pin.version }) {
                    "Library publication cannot encode a version override or prove a transitive-only pin is unchanged: $pin in $domain"
                }
            }
        }
        val dependencies = declared.map { dependency ->
            val coordinate = when (dependency) {
                is Dependency.External -> dependency.coordinate
                is Dependency.Module -> (modules.getValue(dependency.module).publication
                    ?: error("Workspace library dependency requires publication: ${dependency.module}")).let { publication ->
                    if (dependency.module in rootPublicationModules) publication.coordinate else publication.coordinateFor(target)
                }
            }
            val pomCoordinate = if (dependency is Dependency.Module && dependency.module in rootPublicationModules)
                modules.getValue(dependency.module).publication!!.coordinateFor(target) else null
            LibraryDependency(coordinate, dependency.scope == DependencyScopeKind.COMPILE && (NativeTarget.find(target) != null || dependency.exported), pomCoordinate)
        }
        return dependencies.groupBy { it.coordinate.group to it.coordinate.name }.values.map { group ->
            require(group.map { it.coordinate.version }.distinct().size == 1) { "Library publication has conflicting direct dependency versions" }
            val pomCoordinates = group.mapNotNull { it.pomCoordinate }.distinct()
            require(pomCoordinates.size <= 1) { "Library publication has conflicting POM dependency coordinates" }
            LibraryDependency(group.first().coordinate, group.any { it.api }, pomCoordinates.singleOrNull())
        }.sortedBy { it.coordinate.toString() }
    }

    private fun ensurePackage(module: String, target: String): String {
        val id = "$module:$target:package"
        if (id in nodes) return id
        val publication = modules.getValue(module).publication ?: error("Library module requires publication: $module")
        ensureMain(module, target)
        val dependencies = publicationDependencies(module, target)
        val resource = resources(module, target, "main")
        require(target == "jvm" || resource.isEmpty()) { "Native library publication does not yet support resources: $module" }
        val directModules = sourceSets(module, target, "main").flatMap { it.dependencies }.filterIsInstance<Dependency.Module>().map { it.module }.distinct()
        val packaged = directModules.map { ensurePackage(it, target) }.toSet()
        val edges = packaged + mainId(module, target)
        val selection = LibraryPackage(module, target, publication.coordinateFor(target), dependencies,
            if (target == "jvm") jvmTargetFromOptions(target(module, target).compilerOptions) else null,
            rootCoordinate = if (module in rootPublicationModules) publication.coordinate else null)
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            val own = remap(results, module, target, "main")
            val prefix = modulePath(module, target, "main").value
            val compiled = own.map { it.copy(path = LogicalPath(if (target == "jvm") "classes" + it.path.value.removePrefix(prefix) else "module.klib")) }
            val resources = resource.map { it.copy(path = LogicalPath("classes/" + it.path.value.removePrefix("runtime/$module/main/"))) }
            require(compiled.map { it.path }.toSet().intersect(resources.map { it.path }.toSet()).isEmpty()) {
                "Library resource collides with compiled output: $module"
            }
            libraryPackageAction(selection, merge(compiled, resources), composition)
        }
        return id
    }

    private fun commonMetadataId(module: String) = "$module:common:metadata"

    private fun commonArtifacts(module: String): List<LockedArtifact> {
        val domain = ResolutionDomain(module, "common", "main", "kotlin-metadata")
        require(domain in authorized) { "Root publication requires a locked common metadata domain: $module; run resolve" }
        require(policy.exclusionsFor(domain).isEmpty()) { "Common publication cannot encode metadata exclusions: $module" }
        val roots = (commonPublicationDependencyClosure(project, module).external +
            Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20")).distinct()
        val artifacts = roots.flatMap { coordinate ->
            selections[ResolutionRequest(coordinate, VariantRequest("common", "kotlin-metadata", emptyMap()), domain)]?.artifacts
                ?: error("Missing locked common metadata dependency: $coordinate:$module")
        }.distinctBy { artifactPath(it, false) }
        for (pin in policy.pinsFor(domain)) {
            fun matches(value: Coordinate) = value.group == pin.group && value.name == pin.name
            val declared = roots.filter(::matches)
            val selected = artifacts.filter { matches(it.coordinate) }
            if (declared.isEmpty() && selected.isEmpty()) continue
            require(declared.isNotEmpty() && declared.all { it.version == pin.version } && selected.all { it.coordinate.version == pin.version }) {
                "Common publication cannot encode a version override or transitive-only pin: $pin"
            }
        }
        return artifacts
    }

    private fun commonOutputs(results: Map<String, ActionResult>, module: String, prefix: String): List<FileEntry> {
        val outputs = results.getValue(commonMetadataId(module)).outputs.files
        require(outputs.all { it.path.value.startsWith("metadata/") || it.path.value.startsWith("plugins/") }) {
            "Invalid common metadata output: $module"
        }
        val metadata = outputs.filter { it.path.value.startsWith("metadata/") }
        require(metadata.isNotEmpty() && metadata.none { it.executable }) { "Missing or executable common metadata: $module" }
        return metadata.map { it.copy(path = LogicalPath(prefix + "/" + it.path.value.removePrefix("metadata/"))) }
    }

    private fun ensureCommonMetadata(module: String): String {
        val id = commonMetadataId(module)
        if (id in nodes) return id
        val spec = modules.getValue(module)
        val publication = spec.publication ?: error("Common library dependency requires publication: $module")
        require(spec.targets.map { it.name }.toSet() == request.targets) { "Root publication requires every declared target: $module" }
        // A common plugin must cover every published target. Its dependency domain is
        // JVM-hosted regardless of the consumer platform selected below.
        val pluginTarget = if ("jvm" in request.targets) "jvm" else request.targets.sorted().first()
        val applyPlugin = selectedPlugins(module, pluginTarget, "main").singleOrNull()?.targets?.containsAll(request.targets) == true
        val shared = commonPublicationSourceSets(spec)
        require(shared.isNotEmpty()) { "Root publication requires shared main source sets: $module" }
        val sources = shared.flatMap { set -> set.directories.flatMap { directory ->
            require(files.none { it.path == directory }) { "Source directory is a file: ${directory.value}" }
            files.filter { it.path.value.startsWith(directory.value + "/") }.map { file ->
                require(file.path.value.endsWith(".kt")) { "Unsupported common source file: ${file.path.value}" }
                file.copy(path = LogicalPath("src/${file.path.value}"))
            }
        } }.sortedBy { it.path.value }
        require(sources.isNotEmpty() && sources.map { it.path }.distinct().size == sources.size) { "Empty or overlapping common source ownership: $module" }
        val dependencies = commonPublicationDependencyClosure(project, module).modules
        val pluginDependencies = if (applyPlugin) pluginModules(module, pluginTarget, "main") else emptyList()
        pluginDependencies.forEach { ensureMain(it, "jvm") }
        val edges = dependencies.map(::ensureCommonMetadata).toSet() + pluginDependencies.map { mainId(it, "jvm") }
        val artifacts = commonArtifacts(module).map { FileEntry(artifactPath(it, false), it.digest) }.distinct()
        val plugin = if (applyPlugin) plugins(module, pluginTarget, "main") else emptyList()
        require(plugin.none { p -> artifacts.any { it.digest == p.digest } }) { "Compiler plugin cannot be a consumer metadata dependency" }
        val settings = spec.targets.map { compilerOptions(module, it.name, it.compilerOptions) }
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            val dependencyFiles = dependencies.flatMap { commonOutputs(results, it, "modules/$it/metadata") }
            val workspace = if (applyPlugin) workspacePlugin(module, pluginTarget, "main", results) else WorkspacePlugin(emptyList(), emptyList())
            jvmMetadataCompileAction(JvmMetadataCompilation("${publication.coordinate.group}:${publication.coordinate.name}", sources.map { it.path },
                dependencies.map { LogicalPath("modules/$it/metadata") } + artifacts.map { it.path }, LogicalPath("metadata"),
                optIns = settings.flatMap { it.optIns }.distinct().sorted(), explicitApi = settings.any { it.explicitApi },
                warningsAsErrors = settings.any { it.warningsAsErrors }, plugins = plugin.map { it.path }, pluginApplications = workspace.applications, targets = spec.targets.map { it.name }.toSet()),
                merge(sources, dependencyFiles, artifacts, plugin, workspace.entries), composition, tools.getValue("jvm"))
        }
        return id
    }

    private fun ensureRootPackage(module: String): String {
        val id = "$module:common:package"
        if (id in nodes) return id
        val spec = modules.getValue(module)
        val publication = spec.publication ?: error("Library module requires publication: $module")
        val metadata = ensureCommonMetadata(module)
        val targets = spec.targets.sortedBy { it.name }
        val packages = targets.map { ensurePackage(module, it.name) }.toSet()
        val shared = commonPublicationSourceSets(spec)
        val declared = shared.flatMap { it.dependencies }.filter { it.scope == DependencyScopeKind.COMPILE }
        val rootDependencies = declared.filterIsInstance<Dependency.Module>().map { ensureRootPackage(it.module) }.toSet()
        val coordinates = (declared.map { dependency -> when (dependency) {
            is Dependency.External -> dependency.coordinate
            is Dependency.Module -> modules.getValue(dependency.module).publication!!.coordinate
        } } + Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20")).distinct().sortedBy { it.toString() }
        // The selected targets share exactly this intersection. Flattening its source-set DAG
        // produces one common compilation without exposing any platform-only declarations.
        val selection = KmpLibraryPackage(module, publication.coordinate,
            targets.map { LibraryPackage(module, it.name, publication.coordinateFor(it.name), publicationDependencies(module, it.name),
                if (it.name == "jvm") jvmTargetFromOptions(it.compilerOptions) else null, rootCoordinate = publication.coordinate) },
            listOf(KmpSourceSetMetadata("commonMain", emptyList(), coordinates)), targets.associate { it.name to listOf("commonMain") })
        val edges = packages + rootDependencies + metadata
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            kmpLibraryPackageAction(selection, merge(commonOutputs(results, module, "metadata/commonMain"),
                packages.flatMap { results.getValue(it).outputs.files }), composition)
        }
        return id
    }

    private fun ensureFramework(module: String, target: String): String {
        val framework = requireNotNull(target(module, target).nativeFramework)
        val runtimeDependencies = closure(module, target, "main", true).modules
        runtimeDependencies.forEach { ensureMain(it, target) }
        val runtimeIds = listOf(module) + runtimeDependencies
        require(runtimeIds.all { resources(it, target, "main").isEmpty() }) {
            "Native framework resources are not supported: $module:$target; remove main/runtime resources before building the framework"
        }
        val artifacts = artifactEntries(external(module, target, "main", "kotlin-runtime"), target)
        val edges = runtimeIds.map { mainId(it, target) }.toSet()
        val options = options(module, target, "main", mainSources.getValue(module to target))
        val id = "$module:$target:framework"
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            val libraries = runtimeIds.flatMap { remap(results, it, target, "main") }
            nativeCompileAction(NativeCompilation(module, emptyList(),
                libraries = runtimeDependencies.map { modulePath(it, target, "main") } + artifacts.map { it.path },
                output = LogicalPath("${framework.baseName}.framework"), produce = NativeOutput.FRAMEWORK,
                moduleName = options.nativeModuleName, warningsAsErrors = options.warningsAsErrors,
                includedLibrary = modulePath(module, target, "main"), target = NativeTarget.require(target),
                isStaticFramework = framework.isStatic), merge(libraries, artifacts), composition, tools.getValue(target),
                timeoutMillis = target(module, target).nativeTimeouts?.linkMillis ?: 180_000)
        }
        return id
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
                    warningsAsErrors = options.warningsAsErrors, includedLibrary = modulePath(module, target, "main"), entryPoint = application.entryPoint, target = NativeTarget.require(target)),
                    merge(libraries, artifacts), composition, tools.getValue(target), timeoutMillis = target(module, target).nativeTimeouts?.linkMillis ?: 180_000)
            }
            nodes[id] = ActionNode(id, setOf(linkId)) { results ->
                validateResults(results, setOf(linkId))
                val linked = results.getValue(linkId).outputs.files
                require(linked.any { it.path.value == "application.kexe" && it.executable } && linked.all {
                    it.path.value == "application.kexe" || target == NativeTarget.MACOS_ARM64.projectName && it.path.value.startsWith("application.kexe.dSYM/")
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
        val source = if (NativeTarget.find(target) != null) allSources else allSources.let { all ->
            Sources(all.entries.filter { it.path !in main.paths }, all.paths.filter { it !in main.paths }, all.common.filter { it !in main.paths })
        }
        val resource = resources(module, target, "test") + resources(module, target, "main")
        val moduleIds = listOf(module) + dependencies
        val kotest = target(module, target).nativeTestSettings
        val pluginDependencies = pluginModules(module, target, "test")
        pluginDependencies.forEach { ensureMain(it, "jvm") }
        val producerEdges = moduleIds.map { mainId(it, target) }.toSet() + pluginDependencies.map { mainId(it, "jvm") }
        val compileEdges = producerEdges + if (kotest == null) emptySet() else setOf(generationId(module, target))
        val hasTestCompile = NativeTarget.find(target) != null || source.paths.isNotEmpty()
        if (hasTestCompile) {
            val options = options(module, target, "test", source)
            if (NativeTarget.find(target) != null) {
                val production = compilerOptions(compilerModuleName(module, target), target, target(module, target).compilerOptions)
                require(options.optIns.containsAll(production.optIns)) { "Native tests recompile main sources: test opt-ins must include main opt-ins" }
            }
            val artifacts = artifactEntries(external(module, target, "test", if (target == "jvm") "java-api" else "kotlin-api"), target)
            if (kotest != null) {
                val lockedRuntime = external(module, target, "test", "kotlin-api")
                require(lockedRuntime.any { it.coordinate == Coordinate("io.kotest", "kotest-framework-engine-${NativeTarget.require(target).publicationSuffix}", "6.2.5") } &&
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
                nodes[generationId(module, target)] = ActionNode(generationId(module, target), producerEdges) { results ->
                    validateResults(results, producerEdges)
                    val modules = moduleIds.flatMap { remap(results, it, target, "main") }
                    kspNativeAction(KspNativeCompilation(module, options.nativeModuleName, testSources.map { it.path }, source.common.filter { path -> testSources.any { it.path == path } },
                        moduleIds.map { modulePath(it, target, "main") } + artifacts.map { it.path }, ksp.map { it.path }, processors.map { it.path }, target = NativeTarget.require(target)),
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
                val generated = if (kotest == null) emptyList() else results.getValue(generationId(module, target)).outputs.files.also {
                    require(it.map { file -> file.path } == listOf(kotestGeneratedLauncher)) { "KSP prerequisite must contain exactly the raw pinned launcher" }
                }
                val inputs = merge(source.entries, resource, artifacts, plugin, dependencyFiles, workspace.entries, generated)
                val classpath = linkedModules.map { modulePath(it, target, "main") } + artifacts.map { it.path }
                if (target == "jvm") jvmCompileAction(JvmCompilation(module, source.paths, classpath, LogicalPath("classes"), plugin.map { it.path }, source.common,
                    friendPaths = listOf(modulePath(module, target, "main")), jvmTarget = options.jvmTarget, optIns = options.optIns,
                    explicitApi = options.explicitApi, warningsAsErrors = options.warningsAsErrors, jdkRelease = options.jdkRelease, pluginApplications = workspace.applications), inputs, composition, tools.getValue(target))
                else nativeCompileAction(NativeCompilation(module, source.paths + generated.map { it.path }, source.common, classpath, plugin.map { it.path }, LogicalPath("tests.klib"),
                    NativeOutput.LIBRARY, options.nativeModuleName, options.optIns, options.explicitApi, options.warningsAsErrors,
                    kotestLauncher = kotest?.let { kotestGeneratedLauncher }, pluginApplications = workspace.applications, target = NativeTarget.require(target)), inputs, composition, nativeTools(target, workspace.applications),
                    timeoutMillis = target(module, target).nativeTimeouts?.compileMillis ?: 180_000)
            }
            if (NativeTarget.find(target) != null) {
                val linkEdges = runtimeDependencies.map { mainId(it, target) }.toSet() + compileId(module, target)
                val runtimeArtifacts = artifactEntries(external(module, target, "test", "kotlin-runtime"), target)
                nodes[linkId(module, target)] = ActionNode(linkId(module, target), linkEdges) { results ->
                    validateResults(results, linkEdges)
                    val compiled = results.getValue(compileId(module, target)).outputs.files
                    require(compiled.count { it.path == LogicalPath("tests.klib") } == 1 &&
                        compiled.all { it.path == LogicalPath("tests.klib") || it.path.value.startsWith("plugins/") }) { "Native test compilation must produce its klib and declared plugin outputs" }
                    val included = compiled.single { it.path == LogicalPath("tests.klib") }.copy(path = nativeTestLibraryPath(module, target))
                    val dependencyFiles = runtimeDependencies.flatMap { remap(results, it, target, "main") }
                    val inputs = merge(listOf(included), runtimeArtifacts, dependencyFiles)
                    nativeCompileAction(NativeCompilation(module, emptyList(), libraries = runtimeDependencies.map { modulePath(it, target, "main") } + runtimeArtifacts.map { it.path },
                        output = LogicalPath("tests.kexe"), produce = if (kotest == null) NativeOutput.TEST else NativeOutput.KOTEST_TEST,
                        moduleName = options.nativeModuleName, warningsAsErrors = options.warningsAsErrors, includedLibrary = included.path, target = NativeTarget.require(target)), inputs, composition, tools.getValue(target),
                        timeoutMillis = target(module, target).nativeTimeouts?.linkMillis ?: 180_000)
                }
            }
        } else require(selectedPlugins(module, target, "test").isEmpty()) { "Test compiler plugin selected without test compilation sources" }
        val id = "$module:$target:test"
        val runtimeIds = listOf(module) + runtimeDependencies
        val edges = runtimeIds.map { mainId(it, target) }.toSet() + if (hasTestCompile) setOf(if (NativeTarget.find(target) != null) linkId(module, target) else compileId(module, target)) else emptySet()
        val allResources = resource + runtimeDependencies.flatMap { resources(it, target, "main") }
        val runtime = if (target == "jvm") artifactEntries(external(module, target, "test", "java-runtime"), target) else emptyList()
        val testSelection = request.jvmTests[module]
        if (target == "jvm") JvmTestSelection(module, listOf(LogicalPath("validated-placeholder")), testSelection!!.classes, testSelection.engines)
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            val testFiles = if (hasTestCompile) remap(results, module, target, "test") else emptyList()
            if (target == "jvm") {
                val dependencyFiles = runtimeIds.flatMap { remap(results, it, target, "main") }
                val coverage = testSelection!!.coverage?.let { settings ->
                    val sources = runtimeIds.flatMap { producer ->
                        sourceSets(producer, target, "main").flatMap { set -> set.directories.flatMap { directory ->
                            files.filter { it.path.value.startsWith(directory.value + "/") }.map { file ->
                                CoverageSource(producer, set.name, file.path, file.digest)
                            }
                        } }
                    }.sortedWith(compareBy({ it.module }, { it.path.value }, { it.sourceSet }))
                    require(sources.map { it.module to it.path }.distinct().size == sources.size) { "Ambiguous production coverage source ownership" }
                    JvmCoverageSpec(settings.version, settings.agent, settings.reporter,
                        runtimeIds.map { CoverageClassRoot(it, modulePath(it, target, "main")) }, sources, settings.excludes)
                }
                val coverageSources = coverage?.sources?.map { FileEntry(it.inputPath, it.digest) } ?: emptyList()
                val inputs = merge(testFiles, dependencyFiles, allResources, runtime, coverageSources)
                fun resourceRoot(id: String, scope: String) = LogicalPath("runtime/$id/$scope").takeIf { prefix -> allResources.any { it.path.value.startsWith(prefix.value + "/") } }
                val classpath = listOfNotNull(if (hasTestCompile) modulePath(module, target, "test") else null, resourceRoot(module, "test")) +
                    runtimeIds.flatMap { listOfNotNull(modulePath(it, target, "main"), resourceRoot(it, "main")) } + runtime.map { it.path }
                jvmTestAction(JvmTestSelection(module, classpath, testSelection!!.classes, testSelection.engines, coverage), inputs, composition, tools.getValue(target))
            } else if (kotest != null) kotestNativeTestAction(module, modulePath(module, target, "test"), merge(testFiles, allResources), composition, tools.getValue(target), target = NativeTarget.require(target))
            else nativeTestAction(module, modulePath(module, target, "test"), merge(testFiles, allResources), composition, tools.getValue(target), target = NativeTarget.require(target))
        }
        return id
    }
}
