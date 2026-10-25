package fixture.executors

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.ProcessActionExecutor
import kvik.projectgraph.*

class ExecutorPlugin : ProjectGraphContributor, ProjectExecutorContributor, ConfigurableBuildPlugin, ProjectGenerationContributor, ProjectInputContributor {
    override val id = "fixture.executors"
    override val apiVersion = CONTRACT_VERSION
    override val configurationSchema = obj("type" to "object".json())

    override val settingsSchema = obj("type" to "object".json(), "additionalProperties" to false.json(),
        "properties" to obj("label" to obj("type" to "string".json(), "minLength" to 1.json()), "generate" to obj("type" to "boolean".json())))
    override fun validateSettings(settings: JsonValue.Object) {
        val fields = settings.fields(emptySet(), setOf("label", "generate"))
        fields["generate"]?.booleanValue()
        fields["label"]?.let { require(it.stringValue().isNotBlank()) { "label must not be blank" } }
    }

    override fun projectInputs(configuration: ProjectConfiguration): BuildPluginInputs =
        if (configuration.pluginSettings[id]?.values?.get("generate")?.booleanValue() == true)
            BuildPluginInputs(listOf(LogicalPath("schema.txt")), listOf(LogicalPath("templates")))
        else BuildPluginInputs()

    override fun generatedInputs(context: ProjectBuildContext): ProjectGeneratedInputs {
        if (context.settings(id).values["generate"]?.booleanValue() != true) return ProjectGeneratedInputs()
        val producer = "plugin:$id:generate:code"
        val inputs = TreeManifest(context.snapshot.files.filter { it.path.value == "schema.txt" || it.path.value.startsWith("templates/") })
        require(inputs.files.any { it.path.value == "schema.txt" })
        val node = ActionNode(producer, emptySet(), ActionOutputContract(treeOutput("kotlin", "kotlin-sources"), treeOutput("assets", "resources"))) {
            ActionSpec("$id:code-v1", context.composition, ActionKind.GENERATE, mapOf("os" to "macos", "arch" to "arm64"), "jvm",
                mapOf("native-runtime" to context.composition), inputs, listOf(LogicalPath("kotlin"), LogicalPath("assets")), emptyList(), emptyMap())
        }
        return ProjectGeneratedInputs(listOf(node),
            listOf(GeneratedSourceRoot(producer, "app", "main", "build-plugin", "/pluginSettings/$id/generate", LogicalPath("kotlin"))),
            listOf(GeneratedResourceRoot(producer, "app", "main", "/pluginSettings/$id/generate", LogicalPath("assets"), listOf(LogicalPath("generated.txt")))))
    }

    override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph): List<ActionNode> {
        val compile = "app:jvm:main"
        if (graph.nodes.none { it.id == compile }) return emptyList()
        val resources = context.snapshot.files.filter { it.path.value.startsWith("other/resources/") }
        val manifest = ActionNode("$id:manifest", setOf(compile),
            outputContract = ActionOutputContract(listOf(OutputArtifact(LogicalPath("manifest.json"), "plugin-manifest", ArtifactShape.FILE)))) { results ->
            ActionSpec("$id:manifest-v1", context.composition, ActionKind.GENERATE, mapOf("os" to "macos", "arch" to "arm64"), "jvm",
                mapOf("native-runtime" to context.composition), TreeManifest(results.getValue(compile).outputs.files + resources),
                listOf(LogicalPath("manifest.json")), emptyList(), emptyMap(), parameters = context.settings(id))
        }
        val mode = listOf("fail", "timeout", "cancel").singleOrNull { mode -> resources.any { it.path.value.endsWith("/$mode.txt") } }
        val process = mode?.let {
            ActionNode("$id:process", setOf(manifest.id), outputContract = ActionOutputContract(emptyList())) {
                val shell = resources.single { it.path.value.endsWith("/sh") }.copy(path = LogicalPath("tools/sh"), executable = true)
                val sleep = resources.single { it.path.value.endsWith("/sleep") }.copy(path = LogicalPath("tools/sleep"), executable = true)
                ActionSpec("$id:process-v1", context.composition, ActionKind.GENERATE, mapOf("os" to "macos", "arch" to "arm64"), "jvm",
                    mapOf("shell" to shell.digest, "sleep" to sleep.digest), TreeManifest(listOf(shell, sleep)), emptyList(),
                    listOf(mode), mapOf("LANG" to "C", "LC_ALL" to "C"),
                    timeoutMillis = if (mode == "timeout") 1000 else 60_000, cachePolicy = CachePolicy.NEVER)
            }
        }
        return listOfNotNull(manifest, process)
    }

    override fun executors(context: ProjectExecutionContext): Map<String, ActionExecutor> = mapOf(
        "$id:code-v1" to object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(memoryMiB = 32)
            override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction = object : RunningAction {
                override fun poll(): ActionResult {
                    val value = action.inputs.files.sortedBy { it.path.value }.joinToString(":") { it.digest.hash }
                    val source = "package example\nobject Generated { const val value = \"$value\" }\n".encodeToByteArray()
                    val outputs = TreeManifest(listOf(FileEntry(LogicalPath("kotlin/Generated.kt"), context.content.put(source)),
                        FileEntry(LogicalPath("assets/generated.txt"), context.content.put(value.encodeToByteArray()))))
                    return ActionResult(actionId, action.key(Sha256), ActionStatus.EXECUTED, outputs, 0)
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
        },
        "$id:manifest-v1" to object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(memoryMiB = 32)
            override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                NativeFiles.writeNew("${context.workspace}/manifest-started", action.key(Sha256).toJson().canonical().encodeToByteArray())
                return object : RunningAction {
                    override fun poll(): ActionResult {
                        for (file in action.inputs.files) {
                            val bytes = requireNotNull(context.content.get(file.digest))
                            require(Sha256.digest(bytes) == file.digest)
                        }
                        val bytes = obj("kind" to "native-plugin-manifest".json(), "inputs" to action.inputs.toJson(), "settings" to action.parameters).canonical().encodeToByteArray()
                        val outputs = TreeManifest(listOf(FileEntry(LogicalPath("manifest.json"), context.content.put(bytes))))
                        return ActionResult(actionId, action.key(Sha256), ActionStatus.EXECUTED, outputs, 0)
                    }
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        },
        "$id:process-v1" to ProcessActionExecutor(context.runId, context.processes, prepare = { _, action ->
            val directory = "${context.workspace}/process"
            NativeFiles.ensureDirectory(directory)
            for (file in action.inputs.files) {
                val bytes = requireNotNull(context.content.get(file.digest))
                require(Sha256.digest(bytes) == file.digest)
                // macOS system executables cannot reliably run after byte-copy materialization.
                // Bind execution to the declared tool bytes before using the system location.
                val hostTool = "/bin/${file.path.value.substringAfterLast('/')}"
                require(Sha256.digest(NativeFiles.read(hostTool, 16 * 1024 * 1024)) == file.digest)
            }
            val script = if (action.arguments.single() == "fail") "exit 7" else
                $$""""$1" 30 & child=$!; printf '%s %s\n' "$$" "$child" > pids; wait "$child""""
            ProcessRequest(listOf("/bin/sh", "-c", script, "fixture", "/bin/sleep"), directory,
                action.environment, "$directory/stdout", "$directory/stderr", action.timeoutMillis)
        }, collect = { _, _ -> TreeManifest(emptyList()) }, resourceRequest = { ResourceRequest(memoryMiB = 32) }),
    )
}
