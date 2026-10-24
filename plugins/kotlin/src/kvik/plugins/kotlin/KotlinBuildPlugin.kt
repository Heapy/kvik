package kvik.plugins.kotlin

import kvik.core.*

/** Native source plugin; compiler actions are integrated after K03–K08. */
class KotlinBuildPlugin : BuildPlugin {
    override val id: String = "kvik.kotlin"
    override val apiVersion: Int = CONTRACT_VERSION
    override val configurationSchema: JsonValue.Object = obj("type" to "object".json(),
        "properties" to obj("targets" to obj("type" to "array".json(), "items" to obj("enum" to
            (listOf("jvm") + NativeTarget.entries.map { it.projectName } + KotlinJsTarget.entries.map { it.projectName }).map { it.json() }.json()))),
        "required" to listOf("targets".json()).json(), "additionalProperties" to false.json())
    override fun actions(project: JsonValue.Object): List<ActionNode> = error("Compiler adapters have not been integrated")
}
