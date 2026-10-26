@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.core.*
import kvik.foreground.BootstrapQuery
import kvik.locking.effectiveConfiguration
import kvik.projectgraph.ProjectMode

/** Publish a new project generation; never overwrite an existing IDE project or workspace settings. */
internal fun ideaExportCommand(arguments: List<String>): ExitStatus {
    val export = mutableMapOf<String, String>()
    val build = mutableListOf<String>()
    var cursor = 0
    while (cursor < arguments.size) {
        val flag = arguments[cursor++]
        if (flag in setOf("--sdk", "--output")) {
            val value = arguments.getOrNull(cursor++) ?: error("Missing $flag value")
            require(value.isNotBlank() && !value.startsWith("--") && export.put(flag, value) == null) { "Invalid or repeated $flag" }
        } else {
            build += flag
            if (flag != "--offline") build += arguments.getOrNull(cursor++) ?: error("Missing $flag value")
        }
    }
    val options = buildOptions(build)
    require(options.targets == setOf("jvm") && !options.coverage) { "IDEA export currently requires JVM targets" }
    val sdk = export["--sdk"] ?: error("Supply --sdk with the name of an IDEA JDK matching the build tools")
    val output = projectPath(currentDirectory(), export["--output"] ?: error("Supply --output NEW_DIRECTORY"))
    require(NativeFiles.info(output) == null) { "IDEA output already exists: $output" }
    fun requireDisjoint(project: ProjectFile) {
        requireDisjointStore(project, output, projectPath(project.root, options.project.lock), projectPath(project.root, options.tools))
        for (protected in listOf(projectPath(project.root, options.project.store), projectPath(project.root, options.runs), runtimeDirectory()))
            require(!pathsPhysicallyOverlap(output, protected)) { "IDEA output overlaps a store, run directory or runtime" }
    }
    requireDisjoint(readProject(options.project.project))
    val outcome = executeBuild(build, ProjectMode.IDE_INPUTS)
    if (outcome.status != ExitStatus.SUCCESS) return outcome.status
    val project = readProject(options.project.project)
    requireDisjoint(project)
    val directory = checkNotNull(outcome.directory)
    val checked = checkRun(directory)
    if (checked.exitStatus != ExitStatus.SUCCESS) return checked.exitStatus
    val actions = checked.record.requestedChecks.sorted().map { check -> checked.record.actions.single { it.actionId == check } }
    val models = actions.map { readPreparedIdeModel(checked.record, it, directory) }
    val compilations = models.flatMap { it.compilations }.groupBy { it.name }.map { (_, same) ->
        require(same.distinct().size == 1) { "Conflicting IDE compilation models" }; same.first()
    }.sortedBy { it.name }
    require(compilations.none { it.hasCompilerPlugins }) {
        "IDEA compiler-plugin analysis is not implemented; prepare-ide retains the model without claiming editor plugin support"
    }
    require(compilations.none { c -> c.roots.any { it.common } }) {
        "IDEA common-source refinement is not implemented; prepare-ide retains source-set ownership for a future multiplatform import"
    }
    val data = TreeManifest(actions.flatMap { action -> action.outputs.files.filter { it.path.value.startsWith("ide/input/") }.map {
        it.copy(path = LogicalPath(it.path.value.removePrefix("ide/input/")))
    } }.distinct())
    val content = NativeContentStore(projectPath(project.root, options.project.store), maxBlobBytes =
        effectiveConfiguration(project.configuration, options.project.resolverPolicy()).maxArtifactBytes)
    val bootstrap = bootstrapContext()
    val launcher = if (bootstrap == null) listOf("${runtimeDirectory()}/kvik.kexe") else bootstrap.command(BootstrapQuery.LAUNCH).dropLast(1) + "--"
    val forwarded = build.toMutableList()
    val projectIndex = forwarded.indexOf("--project")
    if (projectIndex >= 0) forwarded[projectIndex + 1] = project.path else forwarded += listOf("--project", project.path)
    val runs = listOf(IdeaRun("Kvik build", launcher + "build" + forwarded, currentDirectory())) +
        if (options.modules.all { module -> project.configuration.project.modules.single { it.id == module }.targets.single { it.name == "jvm" }.test != null })
            listOf(IdeaRun("Kvik test", launcher + "test" + forwarded, currentDirectory())) else emptyList()
    val projected = compilations.map { c ->
        IdeaCompilation(c.name, c.roots.map { root -> IdeaRoot(
            if (root.generated) "$output/inputs/${root.path.value}" else "${project.root}/${root.path.value}", root.resource, root.generated) },
            c.modules, c.libraries.map { IdeaLibrary(it.value.substringAfterLast('/'), "$output/inputs/${it.value}") },
            jvmTargetFromOptions(c.compilerOptions), c.compilerOptions,
            if (c.compilation == "test") "${c.module}.main" else null, test = c.compilation == "test")
    }
    val files = ideaProjectFiles(sdk, projected, runs)
    val parent = output.substringBeforeLast('/')
    NativeFiles.ensureDirectory(parent)
    val pending = NativeFiles.tempDirectory(parent)
    try {
        NativeFiles.ensureDirectory("$pending/project")
        NativeOutputTrees(content).materialize(data, "$pending/project/inputs")
        for (root in compilations.flatMap { it.roots }.distinct()) {
            if (root.generated) NativeFiles.ensureDirectory("$pending/project/inputs/${root.path.value}")
            else {
                val path = "${project.root}/${root.path.value}"
                if (NativeFiles.info(path) != null) NativeFiles.ensureDirectory(path, create = false)
            }
        }
        for ((path, text) in files) {
            val full = "$pending/project/$path"
            NativeFiles.ensureDirectory(full.substringBeforeLast('/'))
            NativeFiles.writeNew(full, text.encodeToByteArray())
            // IDEA owns its settings after import (SDK selection, run configurations,
            // format upgrades). Prepared libraries and generated source bytes stay read-only.
            if (path.endsWith(".xml") || path.endsWith(".iml"))
                check(platform.posix.chmod(full, 384u) == 0) { "Cannot make IDEA settings writable: $path" }
        }
        val current = checkRun(directory)
        if (current.exitStatus != ExitStatus.SUCCESS) return current.exitStatus
        NativeFiles.exposeDirectory("$pending/project", output)
    } finally { NativeFiles.removeTree(pending) }
    println(obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "idea-export".json(),
        "directory" to output.json(), "preparation" to directory.json(), "sdk" to sdk.json(),
        "compilations" to compilations.map { it.name.json() }.json()).canonical())
    return ExitStatus.SUCCESS
}
