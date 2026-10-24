package kvik.cli

import kvik.bsp.*
import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.compiler.ksp.KspNativeCompilation
import kvik.compiler.ksp.kotestGeneratedLauncher
import kvik.compiler.ksp.kspNativeAction
import kvik.compiler.native.adaptKotestLauncher
import kvik.compiler.sqldelight.SqlDelightGeneration
import kvik.compiler.sqldelight.sqlDelightAction
import kvik.core.*
import kvik.runevidence.explainRetainedAction

/** Publish only the supported producer's verified, original source into a new retained BSP tree. */
internal fun materializeBspGeneratedSources(
    model: BspProjectModel,
    record: VerificationRecord,
    events: List<RunEvent>,
    runDirectory: String,
    childDirectory: String,
    store: String,
): Map<BspTarget, List<BspGeneratedSources>> {
    require(record.freshnessAtCompletion == Freshness.CURRENT)
    val generated = linkedMapOf<BspTarget, MutableList<BspGeneratedSources>>()
    for (result in record.actions) {
        val targets = model.generatedSourceTargets(result.actionId)
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
            else -> error("Unsupported generated source producer")
        }
        require(expected.toJson() == action && expected.key(Sha256) == result.key) { "Generated source action differs from the supported contract" }
        val owners = targets.map { generated.getOrPut(it) { mutableListOf() } }
        if (result.outputs.files.isEmpty()) continue
        val content = NativeContentStore(store, maxBlobBytes = 8 * 1024 * 1024)
        for (output in result.outputs.files) {
            val bytes = content.get(output.digest) ?: error("Generated source content is unavailable")
            val source = bytes.decodeToString(throwOnInvalidSequence = true)
            if (implementation == "kvik-ksp-native-v1") adaptKotestLauncher(source)
        }
        val directory = "$childDirectory/generated-$name"
        NativeOutputTrees(content).materialize(result.outputs, directory)
        val sources = BspGeneratedSources(directory, result.actionId, checkNotNull(result.key), result.outputs)
        owners.forEach { it += sources }
    }
    return generated
}
