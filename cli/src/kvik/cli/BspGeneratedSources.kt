package kvik.cli

import kvik.bsp.*
import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.compiler.ksp.KspNativeCompilation
import kvik.compiler.ksp.kotestGeneratedLauncher
import kvik.compiler.ksp.kspNativeAction
import kvik.compiler.native.kotestPreparedSources
import kvik.compiler.sqldelight.SqlDelightGeneration
import kvik.compiler.sqldelight.sqlDelightAction
import kvik.compiler.jvm.*
import kvik.core.*
import kvik.locking.ResolverPolicy
import kvik.runevidence.explainRetainedAction

internal data class BspGeneratedOutputs(
    val sources: Map<BspTarget, List<BspGeneratedSources>>,
    val resources: Map<BspTarget, List<BspGeneratedResources>>,
)

/** Publish supported producers' verified outputs into a new retained BSP tree. */
internal fun materializeBspGeneratedOutputs(
    model: BspProjectModel,
    record: VerificationRecord,
    events: List<RunEvent>,
    runDirectory: String,
    childDirectory: String,
    store: String,
    policy: ResolverPolicy,
): BspGeneratedOutputs {
    require(record.freshnessAtCompletion == Freshness.CURRENT)
    val generated = linkedMapOf<BspTarget, MutableList<BspGeneratedSources>>()
    val resources = linkedMapOf<BspTarget, MutableList<BspGeneratedResources>>()
    val plugins = materializeBspPluginOutputs(model, record, events, runDirectory, childDirectory, store, policy)
    plugins.sources.forEach { (target, values) -> generated.getOrPut(target) { mutableListOf() }.addAll(values) }
    plugins.resources.forEach { (target, values) -> resources.getOrPut(target) { mutableListOf() }.addAll(values) }
    for (result in record.actions) {
        if (model.pluginGeneratedRoots(result.actionId).isNotEmpty()) continue
        val sourceTargets = model.generatedSourceTargets(result.actionId)
        val resourceTargets = model.generatedResourceTargets(result.actionId)
        val targets = sourceTargets + resourceTargets
        val target = targets.firstOrNull() ?: continue
        if (!result.successful) continue
        val name = Sha256.digest(result.actionId.encodeToByteArray()).hash
        val retained = parseJson(NativeFiles.read("$runDirectory/actions/$name.json", 16 * 1024 * 1024)
            .decodeToString(throwOnInvalidSequence = true))
        val action = explainRetainedAction(record, events, result.actionId, retained).values.getValue("action")
        val fields = action.objectValue()
        val inputs = TreeManifest.fromJson(fields.getValue("inputs"))
        val toolchains = fields.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) }
        val timeout = fields.getValue("timeoutMillis").longValue()
        val implementation = fields.getValue("implementation").stringValue()
        val expected = when (implementation) {
            "kvik-ksp-native-v1" -> {
                val compilation = KspNativeCompilation.fromJson(fields.getValue("parameters"))
                require(compilation.module == target.module && compilation.target.projectName == target.target)
                val output = result.outputs.files.single()
                require(output.path == kotestGeneratedLauncher && !output.executable && output.digest.sizeBytes in 1..1024 * 1024) {
                    "Unsupported KSP generated source output"
                }
                kspNativeAction(compilation, inputs, record.composition, toolchains, timeout)
            }
            "kvik-sqldelight-v1" -> {
                val generation = SqlDelightGeneration.fromJson(fields.getValue("parameters"))
                val module = model.inspection.project.project.modules.single { it.id == target.module }
                require(generation.module == target.module && targets.all { it.compilation == "main" } &&
                    generation.settings == module.sqlDelight) { "SQLDelight generation differs from imported configuration" }
                require(result.outputs.files.size <= 10_000 && result.outputs.files.all {
                    !it.executable && it.path.value.startsWith("${generation.output.value}/") &&
                        it.path.value.endsWith(".kt") && it.digest.sizeBytes <= 8L * 1024 * 1024
                } && result.outputs.files.sumOf { it.digest.sizeBytes } <= 64L * 1024 * 1024) {
                    "Unsupported SQLDelight generated source output"
                }
                require(generation.sources.isEmpty() == result.outputs.files.isEmpty()) {
                    "SQLDelight output does not match SQL source inventory"
                }
                sqlDelightAction(generation, inputs, record.composition, toolchains, timeout)
            }
            "kvik-jvm-script-generator-v1", "kvik-kotlin-script-generator-v1" -> {
                val script = requireNotNull(model.scriptGenerator(result.actionId))
                val generator = checkNotNull(script.declaration.generator)
                val parameters = fields.getValue("parameters").objectValue()
                val outputs = parameters["resourceOutputs"]?.arrayValue()?.map { LogicalPath(it.stringValue()) }.orEmpty()
                require(outputs == generator.resources && timeout == script.declaration.timeoutMillis) {
                    "Script generation differs from imported configuration"
                }
                val environment = fields.getValue("environment").objectValue().mapValues { it.value.stringValue() }
                require(environment == script.declaration.environment)
                require(result.outputs.files.size <= 10_000 && result.outputs.files.all {
                    !it.executable && it.path.value.startsWith("generated/") &&
                        (outputs.isNotEmpty() || it.path.value.endsWith(".kt")) && it.digest.sizeBytes <= 8L * 1024 * 1024
                } && result.outputs.files.sumOf { it.digest.sizeBytes } <= 64L * 1024 * 1024) { "Unsupported script generator outputs" }
                if (outputs.isNotEmpty()) require(result.outputs.files.map { it.path }.toSet() ==
                    outputs.map { LogicalPath("generated/${it.value}") }.toSet()) { "Script resource outputs differ from declaration" }
                val launchParameters = JsonValue.Object(parameters - "resourceOutputs")
                val arguments = listOf("generated", "inputs") + generator.arguments
                if (implementation == "kvik-jvm-script-generator-v1") {
                    val launch = JvmScriptLaunch.fromJson(launchParameters)
                    require(script.declaration.entryScript == null && launch.name == script.name &&
                        launch.arguments == arguments && launch.mainClass == script.declaration.mainClass)
                    jvmScriptGeneratorAction(launch, inputs, record.composition, toolchains, environment, timeout, outputs)
                } else {
                    val launch = KtsLaunch.fromJson(launchParameters)
                    require(script.declaration.entryScript != null && launch.name == script.name && launch.arguments == arguments)
                    ktsScriptGeneratorAction(launch, inputs, record.composition, toolchains, environment, timeout, outputs)
                }
            }
            else -> error("Unsupported generator implementation")
        }
        require(expected.toJson() == action && expected.key(Sha256) == result.key) { "Generator action differs from the supported contract" }
        val owners = sourceTargets.map { generated.getOrPut(it) { mutableListOf() } }
        val resourceOwners = resourceTargets.map { resources.getOrPut(it) { mutableListOf() } }
        if (result.outputs.files.isEmpty()) continue
        val content = NativeContentStore(store, maxBlobBytes = 8 * 1024 * 1024)
        val diagnosticFiles = mutableListOf<FileEntry>()
        for (output in result.outputs.files) {
            val bytes = content.get(output.digest) ?: error("Generated content is unavailable")
            if (sourceTargets.isNotEmpty()) {
                val source = bytes.decodeToString(throwOnInvalidSequence = true)
                if (implementation == "kvik-ksp-native-v1") kotestPreparedSources(source).forEach { (path, text) ->
                    diagnosticFiles += FileEntry(path, content.put(text.encodeToByteArray()))
                }
            }
        }
        val directory = "$childDirectory/generated-$name"
        // Match the paths used by consuming compilers, so diagnostics resolve to these files.
        val script = model.scriptGenerator(result.actionId)
        val files = if (script == null || sourceTargets.isEmpty()) result.outputs else TreeManifest(result.outputs.files.map {
            it.copy(path = LogicalPath("generated/scripts/${script.name}/" + it.path.value.removePrefix("generated/")))
        })
        NativeOutputTrees(content).materialize(files, directory)
        val diagnostics = TreeManifest(diagnosticFiles)
        if (diagnosticFiles.isNotEmpty()) NativeOutputTrees(content).materialize(diagnostics, "$directory-diagnostics")
        if (owners.isNotEmpty()) {
            val sources = BspGeneratedSources(directory, result.actionId, checkNotNull(result.key), files, diagnostics)
            owners.forEach { it += sources }
        }
        if (resourceOwners.isNotEmpty()) {
            val generatedResources = BspGeneratedResources(directory, result.actionId, checkNotNull(result.key), files)
            resourceOwners.forEach { it += generatedResources }
        }
    }
    return BspGeneratedOutputs(generated, resources)
}
