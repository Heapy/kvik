package kvik.projectgraph

import kvik.core.*
import kvik.compiler.jvm.*
import kvik.compiler.native.*
import kvik.compiler.js.*

/** Static runner settings; runtime paths are relative to the action's staged inputs.
 * Resolving an action still requires the actual prerequisite files and their digests.
 */
sealed interface TestLaunch {
    val module: String
    val target: String
    val implementation: String
    val timeoutMillis: Long
    val resources: TestResources
    val parameters: JsonValue.Object
    val outputContract: ActionOutputContract
    fun action(inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>): ActionSpec
    fun requireMatches(action: ActionSpec) {
        require(action == this.action(action.inputs, action.composition, action.toolchains)) { "Test action differs from its declared launch settings" }
    }
    fun toJson(): JsonValue.Object = obj(
        "implementation" to implementation.json(), "module" to module.json(), "target" to target.json(),
        "parameters" to parameters, "timeoutMillis" to timeoutMillis.json(), "cachePolicy" to "never".json(),
        "resources" to JsonValue.Object(resources.toJson().values + ("cpuSlots" to resources.cpuSlots.json())),
    )
}

class JvmTestLaunch(selection: JvmTestSelection, override val timeoutMillis: Long = 60_000) : TestLaunch {
    val selection = JvmTestSelection.fromJson(selection.toJson())
    init { require(timeoutMillis > 0) }
    override val module get() = selection.module
    override val target = "jvm"
    override val implementation = "kvik-jvm-tests-v1"
    override val resources get() = selection.resources
    override val parameters get() = selection.toJson()
    override val outputContract get() = ActionOutputContract(listOf(fileOutput("test-results.json", "test-results")) +
        (if (selection.coverage == null) emptyList() else listOf(fileOutput("coverage.exec", "coverage-execution"),
            fileOutput("coverage-result.json", "coverage-results"))) +
        selection.agents.flatMap { agent -> agent.outputs.map { fileOutput(it.value, "test-instrumentation") } })
    override fun action(inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>) =
        jvmTestAction(selection, inputs, composition, toolchains, timeoutMillis)
}

class NativeTestLaunch(
    override val module: String, val nativeTarget: NativeTarget, val executable: LogicalPath,
    val kotest: Boolean = false, val filter: String? = null,
    resources: TestResources = TestResources(), override val timeoutMillis: Long = 60_000,
) : TestLaunch {
    override val resources = resources.copy(exclusiveResources = resources.exclusiveResources.toSet())
    init {
        require(timeoutMillis > 0 && module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(nativeTarget.supportsHostExecution) { "iOS test execution is unsupported" }
        require(!kotest || filter == null) { "Kotest Native filtering is unsupported" }
        require(filter == null || filter.matches(Regex("[A-Za-z0-9_.*]+"))) { "Unsupported test filter" }
    }
    override val outputContract get() = ActionOutputContract(fileOutput("test-results.json", "test-results"))
    override val target get() = nativeTarget.projectName
    override val implementation get() = if (kotest) "kvik-kotest-native-tests-v1" else "kvik-native-tests-v1"
    override val parameters get() = JsonValue.Object(
        obj("module" to module.json(), "executable" to executable.value.json()).values +
            (if (kotest) emptyMap() else mapOf("filter" to (filter?.json() ?: JsonValue.Null))) +
            (if (resources == TestResources()) emptyMap() else mapOf("resources" to resources.toJson())))
    override fun action(inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>) =
        if (kotest) kotestNativeTestAction(module, executable, inputs, composition, toolchains, timeoutMillis, nativeTarget, resources)
        else nativeTestAction(module, executable, inputs, composition, toolchains, filter, timeoutMillis, nativeTarget, resources)
}

class JsTestLaunch(selection: JsTestSelection, override val timeoutMillis: Long = 60_000) : TestLaunch {
    val selection = JsTestSelection.fromJson(selection.toJson())
    init { require(timeoutMillis > 0) }
    override val module get() = selection.module
    override val outputContract get() = ActionOutputContract(fileOutput("test-results.json", "test-results"))
    override val target get() = selection.target.projectName
    override val implementation = "kvik-js-tests-v1"
    override val resources get() = selection.resources
    override val parameters get() = selection.toJson()
    override fun action(inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>) =
        jsTestAction(selection, inputs, composition, toolchains, timeoutMillis)
}
