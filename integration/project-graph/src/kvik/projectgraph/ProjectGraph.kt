package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.native.*

enum class ProjectMode { BUILD, TEST }
data class JvmTests(val classes: List<String>, val engines: List<String> = listOf("junit-jupiter"))
data class ProjectGraphRequest(
    val modules: Set<String>,
    val targets: Set<String>,
    val mode: ProjectMode,
    val jvmTests: Map<String, JvmTests> = emptyMap(),
)
data class ProjectActionGraph(val nodes: List<ActionNode>, val requestedChecks: Set<String>, val terminalActions: Map<String, String>)

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
    private val selections = lock.selections.associateBy { ResolutionRequest(it.requested, it.variant) }
    private val authorized = effective.document.objectValue().getValue("scopes").arrayValue().map { value ->
        val s = value.objectValue()
        DependencyScope(s.getValue("module").stringValue(), s.getValue("target").stringValue(), s.getValue("scope").stringValue(), s.getValue("usage").stringValue())
    }.toSet()
    private val nodes = linkedMapOf<String, ActionNode>()
    private val mainSources = mutableMapOf<Pair<String, String>, Sources>()

    init {
        require(lock.configuration == effective.digest) { "Stale dependency lock" }
        require(selections.size == lock.selections.size && lock.selections.map { ResolutionRequest(it.requested, it.variant) } == effective.requests) { "Dependency lock request identities differ" }
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
            val usages = if (target == "jvm") if (scope == "test") listOf("java-api", "java-runtime") else listOf("java-api") else listOf("kotlin-api")
            for (usage in usages) require(DependencyScope(module, target, scope, usage) in authorized ||
                scope == "main" && DependencyScope(module, target, "test", usage) in authorized) { "Lock configuration does not authorize $module:$target:$scope:$usage" }
        }
    }

    fun build(): ProjectActionGraph {
        val terminals = linkedMapOf<String, String>()
        for (module in request.modules.sorted()) for (target in request.targets.sorted()) {
            ensureMain(module, target)
            val check = "$module:$target:${request.mode.name.lowercase()}"
            terminals[check] = if (request.mode == ProjectMode.BUILD) mainId(module, target) else ensureTest(module, target)
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
    private fun moduleClosure(module: String, target: String, scope: String): List<String> {
        val seen = linkedSetOf<String>()
        fun visit(id: String, selectedScope: String) {
            for (dependency in sourceSets(id, target, selectedScope).flatMap { it.dependencies }.filterIsInstance<Dependency.Module>()) {
                if (seen.add(dependency.module)) visit(dependency.module, "main")
            }
        }
        visit(module, scope)
        return seen.toList()
    }
    private fun external(module: String, target: String, scope: String, usage: String): List<LockedArtifact> {
        val all = listOf(module to scope) + moduleClosure(module, target, scope).map { it to "main" }
        val roots = all.flatMap { (id, selectedScope) -> sourceSets(id, target, selectedScope).flatMap { it.dependencies }.filterIsInstance<Dependency.External>().map { it.coordinate } }.distinct()
        return roots.flatMap { locked(it, target, usage).artifacts }.distinctBy { artifactPath(it, false) }
    }
    private fun locked(coordinate: Coordinate, target: String, usage: String): LockedSelection =
        selections[ResolutionRequest(coordinate, VariantRequest(target, usage, emptyMap()))] ?: error("Missing locked dependency: $coordinate:$target:$usage")
    private fun artifactPath(artifact: LockedArtifact, plugin: Boolean): LogicalPath {
        val extension = artifact.url.substringAfterLast('.', "")
        require(extension in setOf("jar", "klib")) { "Unsupported locked artifact: ${artifact.url}" }
        return LogicalPath("${if (plugin) "plugins" else "deps"}/${artifact.digest.hash}-${artifact.digest.sizeBytes}.$extension")
    }
    private fun artifactEntries(artifacts: List<LockedArtifact>, target: String, plugin: Boolean = false): List<FileEntry> = artifacts.map {
        require(it.url.endsWith(if (target == "jvm" || plugin) ".jar" else ".klib")) { "Wrong artifact kind for $target: ${it.url}" }
        FileEntry(artifactPath(it, plugin), it.digest)
    }.distinct()
    private fun plugins(module: String, target: String, scope: String): List<FileEntry> {
        val selected = modules.getValue(module).compilerPlugins.filter { target in it.targets &&
            (scope in it.scopes || target == "macosArm64" && scope == "test" && "main" in it.scopes) }.distinct()
        require(selected.all { it.module == null }) { "Workspace compiler plugins require K13" }
        require(selected.all { it.options.isEmpty() && it.inputs.isEmpty() }) { "Serialization plugin options/extra inputs are unsupported" }
        val roots = selected.map { it.coordinate!! }.distinct()
        require(roots.size <= 1) { "Multiple compiler plugins are unsupported" }
        return roots.flatMap { coordinate ->
            require(coordinate == Coordinate("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.20")) { "Only pinned serialization compiler plugin 2.4.20 is supported" }
            val artifacts = locked(coordinate, "jvm", "java-runtime").artifacts
            require(artifacts.size == 1 && artifacts.single().coordinate == coordinate) { "Unsupported serialization plugin closure/pin" }
            artifactEntries(artifacts, "jvm", true)
        }
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
    private fun modulePath(module: String, target: String, scope: String) = LogicalPath("modules/$module/$target/$scope" + if (target == "jvm") "" else if (scope == "main") ".klib" else "/tests.kexe")
    private fun remap(results: Map<String, ActionResult>, module: String, target: String, scope: String): List<FileEntry> {
        val id = if (scope == "main") mainId(module, target) else compileId(module, target)
        val result = results[id] ?: error("Missing prerequisite result: $id")
        require(result.actionId == id && result.successful) { "Unsuccessful/mismatched prerequisite: $id" }
        val output = if (target == "jvm") "classes" else if (scope == "main") "module.klib" else "tests.kexe"
        require(result.outputs.files.isNotEmpty()) { "Empty prerequisite outputs: $id" }
        val prefix = modulePath(module, target, scope).value
        if (target == "macosArm64" && scope == "main") {
            require(result.outputs.files.size == 1 && result.outputs.files.single().path.value == output) { "Unexpected Native library output" }
        } else require(result.outputs.files.all { it.path.value.startsWith("$output/") && target == "jvm" ||
            target == "macosArm64" && (it.path.value == output || it.path.value.startsWith("$output.dSYM/")) }) { "Unexpected prerequisite output: $id" }
        return result.outputs.files.map { it.copy(path = LogicalPath(prefix + it.path.value.removePrefix(output))) }
    }
    private fun validateResults(results: Map<String, ActionResult>, dependencies: Set<String>) {
        require(results.keys == dependencies && results.all { (id, result) -> result.actionId == id && result.successful }) { "Prerequisite result set/status differs" }
    }
    private fun options(module: String, target: String, sources: Sources): CompilerOptions = compilerOptions(module, target, target(module, target).compilerOptions).also {
        require(!it.multiplatform || sources.common.isNotEmpty()) { "Explicit multiplatform option requires common fragments" }
    }

    private fun ensureMain(module: String, target: String) {
        val id = mainId(module, target)
        if (id in nodes) return
        val dependencies = moduleClosure(module, target, "main")
        dependencies.forEach { ensureMain(it, target) }
        val source = sources(module, target, "main")
        require(source.paths.isNotEmpty()) { "No Kotlin main sources: $module:$target" }
        mainSources[module to target] = source
        val options = options(module, target, source)
        val resource = resources(module, target, "main")
        val artifacts = artifactEntries(external(module, target, "main", if (target == "jvm") "java-api" else "kotlin-api"), target)
        val plugin = plugins(module, target, "main")
        require(plugin.none { p -> artifacts.any { it.digest == p.digest } }) { "Compiler plugin also appears in consumer classpath" }
        val edges = dependencies.map { mainId(it, target) }.toSet()
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            val dependencyFiles = dependencies.flatMap { remap(results, it, target, "main") }
            val inputs = merge(source.entries, resource, artifacts, plugin, dependencyFiles)
            val classpath = dependencies.map { modulePath(it, target, "main") } + artifacts.map { it.path }
            if (target == "jvm") jvmCompileAction(JvmCompilation(module, source.paths, classpath, LogicalPath("classes"), plugin.map { it.path }, source.common,
                jvmTarget = options.jvmTarget, optIns = options.optIns, explicitApi = options.explicitApi, warningsAsErrors = options.warningsAsErrors), inputs, composition, tools.getValue(target))
            else nativeCompileAction(NativeCompilation(module, source.paths, source.common, classpath, plugin.map { it.path }, LogicalPath("module.klib"),
                moduleName = options.nativeModuleName, optIns = options.optIns, explicitApi = options.explicitApi, warningsAsErrors = options.warningsAsErrors), inputs, composition, tools.getValue(target))
        }
    }

    private fun ensureTest(module: String, target: String): String {
        val dependencies = moduleClosure(module, target, "test")
        dependencies.forEach { ensureMain(it, target) }
        val main = mainSources.getValue(module to target)
        val allSources = sources(module, target, "test")
        val source = if (target == "macosArm64") allSources else allSources.let { all ->
            Sources(all.entries.filter { it.path !in main.paths }, all.paths.filter { it !in main.paths }, all.common.filter { it !in main.paths })
        }
        val resource = resources(module, target, "test") + resources(module, target, "main")
        val moduleIds = listOf(module) + dependencies
        val compileEdges = moduleIds.map { mainId(it, target) }.toSet()
        val hasTestCompile = target == "macosArm64" || source.paths.isNotEmpty()
        if (hasTestCompile) {
            val options = options(module, target, source)
            val artifacts = artifactEntries(external(module, target, "test", if (target == "jvm") "java-api" else "kotlin-api"), target)
            val plugin = plugins(module, target, "test")
            require(plugin.none { p -> artifacts.any { it.digest == p.digest } }) { "Compiler plugin also appears in consumer classpath" }
            nodes[compileId(module, target)] = ActionNode(compileId(module, target), compileEdges) { results ->
                validateResults(results, compileEdges)
                val linkedModules = if (target == "jvm") moduleIds else dependencies
                val dependencyFiles = linkedModules.flatMap { remap(results, it, target, "main") }
                val inputs = merge(source.entries, resource, artifacts, plugin, dependencyFiles)
                val classpath = linkedModules.map { modulePath(it, target, "main") } + artifacts.map { it.path }
                if (target == "jvm") jvmCompileAction(JvmCompilation(module, source.paths, classpath, LogicalPath("classes"), plugin.map { it.path }, source.common,
                    friendPaths = listOf(modulePath(module, target, "main")), jvmTarget = options.jvmTarget, optIns = options.optIns,
                    explicitApi = options.explicitApi, warningsAsErrors = options.warningsAsErrors), inputs, composition, tools.getValue(target))
                else nativeCompileAction(NativeCompilation(module, source.paths, source.common, classpath, plugin.map { it.path }, LogicalPath("tests.kexe"), NativeOutput.TEST,
                    options.nativeModuleName, options.optIns, options.explicitApi, options.warningsAsErrors), inputs, composition, tools.getValue(target))
            }
        } else require(plugins(module, target, "test").isEmpty()) { "Test compiler plugin selected without test compilation sources" }
        val id = "$module:$target:test"
        val edges = compileEdges + if (hasTestCompile) setOf(compileId(module, target)) else emptySet()
        val allResources = resource + dependencies.flatMap { resources(it, target, "main") }
        val runtime = if (target == "jvm") artifactEntries(external(module, target, "test", "java-runtime"), target) else emptyList()
        val testSelection = request.jvmTests[module]
        if (target == "jvm") JvmTestSelection(module, listOf(LogicalPath("validated-placeholder")), testSelection!!.classes, testSelection.engines)
        nodes[id] = ActionNode(id, edges) { results ->
            validateResults(results, edges)
            val testFiles = if (hasTestCompile) remap(results, module, target, "test") else emptyList()
            if (target == "jvm") {
                val dependencyFiles = moduleIds.flatMap { remap(results, it, target, "main") }
                val inputs = merge(testFiles, dependencyFiles, allResources, runtime)
                fun resourceRoot(id: String, scope: String) = LogicalPath("runtime/$id/$scope").takeIf { prefix -> allResources.any { it.path.value.startsWith(prefix.value + "/") } }
                val classpath = listOfNotNull(if (hasTestCompile) modulePath(module, target, "test") else null, resourceRoot(module, "test")) +
                    moduleIds.flatMap { listOfNotNull(modulePath(it, target, "main"), resourceRoot(it, "main")) } + runtime.map { it.path }
                jvmTestAction(JvmTestSelection(module, classpath, testSelection!!.classes, testSelection.engines), inputs, composition, tools.getValue(target))
            } else nativeTestAction(module, modulePath(module, target, "test"), merge(testFiles, allResources), composition, tools.getValue(target))
        }
        return id
    }
}
