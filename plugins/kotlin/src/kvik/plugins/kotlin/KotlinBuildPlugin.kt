package kvik.plugins.kotlin

import kvik.core.*
import kvik.projectgraph.*

/** Source-distributed Kotlin provider for the project and script action graphs. */
class KotlinBuildPlugin : ProjectBuildPlugin {
    override val id: String = "kvik.kotlin"
    override val apiVersion: Int = CONTRACT_VERSION
    override val configurationSchema: JsonValue.Object = obj(
        "description" to "Kotlin consumes the core project or standalone script authoring document.".json(),
        "oneOf" to listOf("project", "standaloneScript").map {
            obj("\$ref" to "https://kvik.dev/schemas/v1/contracts.json#/\$defs/$it".json())
        }.json())
    override fun projectGraph(context: ProjectBuildContext, request: ProjectGraphRequest): ProjectActionGraph =
        projectActionGraph(context.effective, context.lock, context.snapshot, context.composition, context.toolchains, request)

    override fun scriptGraph(context: ProjectBuildContext, request: ScriptGraphRequest): ProjectActionGraph =
        scriptActionGraph(context.effective, context.lock, context.snapshot, context.composition,
            context.toolchains.getValue("jvm"), request)
}
