package kvik.cli

import kvik.bsp.*
import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.compiler.ksp.KspNativeCompilation
import kvik.compiler.ksp.kotestGeneratedLauncher
import kvik.compiler.ksp.kspNativeAction
import kvik.compiler.native.adaptKotestLauncher
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
        val target = model.generatedSourceTarget(result.actionId) ?: continue
        if (!result.successful) continue
        val name = Sha256.digest(result.actionId.encodeToByteArray()).hash
        val retained = parseJson(NativeFiles.read("$runDirectory/actions/$name.json", 16 * 1024 * 1024)
            .decodeToString(throwOnInvalidSequence = true))
        val action = explainRetainedAction(record, events, result.actionId, retained).values.getValue("action")
        val fields = action.objectValue()
        require(fields.getValue("implementation").stringValue() == "kvik-ksp-native-v1") { "Unsupported generated source producer" }
        val compilation = KspNativeCompilation.fromJson(fields.getValue("parameters"))
        require(compilation.module == target.module && compilation.target.projectName == target.target)
        val expected = kspNativeAction(compilation, TreeManifest.fromJson(fields.getValue("inputs")), record.composition,
            fields.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) }, fields.getValue("timeoutMillis").longValue())
        require(expected.toJson() == action && expected.key(Sha256) == result.key) { "Generated source action differs from the supported contract" }
        val output = result.outputs.files.single()
        require(output.path == kotestGeneratedLauncher && !output.executable && output.digest.sizeBytes in 1..1024 * 1024) {
            "Unsupported generated source output"
        }
        val content = NativeContentStore(store, maxBlobBytes = 1024 * 1024)
        val bytes = content.get(output.digest) ?: error("Generated source content is unavailable")
        adaptKotestLauncher(bytes.decodeToString(throwOnInvalidSequence = true))
        val directory = "$childDirectory/generated-$name"
        NativeOutputTrees(content).materialize(result.outputs, directory)
        generated.getOrPut(target) { mutableListOf() } += BspGeneratedSources(directory, result.actionId,
            checkNotNull(result.key), result.outputs)
    }
    return generated
}
