package kvik.application

import kvik.core.*

/** Packages only the caller's resolved runtime closure. This action never starts an application. */
fun applicationBundleAction(
    launch: ApplicationLaunch,
    inputs: TreeManifest,
    composition: Digest,
    toolchains: Map<String, Digest>,
): ActionSpec {
    // Reparse to validate and detach caller-owned argument/environment/classpath collections.
    val metadata = ApplicationLaunch.fromJson(parseJson(launch.toJson().canonical()))
    val declared = TreeManifest(inputs.files.toList())
    metadata.requireInputs(declared)
    return ActionSpec(
        implementation = "kvik-application-bundle-v1",
        composition = composition,
        kind = ActionKind.PACKAGE,
        platform = mapOf("format" to "application-bundle-v1"),
        target = metadata.target,
        toolchains = toolchains.toMap(),
        inputs = declared,
        outputs = listOf(LogicalPath("application")),
        arguments = emptyList(),
        environment = emptyMap(),
        parameters = metadata.toJson(),
        timeoutMillis = 120_000,
        cachePolicy = CachePolicy.LOCAL,
    )
}
