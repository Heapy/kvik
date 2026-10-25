package kvik.compiler.jvm

import kvik.core.*
import kotlin.test.*

class KtsActionsTest {
    private val identity = Sha256.digest(byteArrayOf())
    private val tools = jvmIdentities.associateWith { identity }
    private val primary = LogicalPath("src/task.kts")
    private val compilation = KtsCompilation("task", primary, listOf(primary), emptyList(), LogicalPath("classes"))
    private fun inputs(vararg paths: String) = TreeManifest(paths.map { FileEntry(LogicalPath(it), identity) })

    @Test fun separateAdapterPreservesOrdinaryKotlinSourceValidation() {
        assertFails { JvmCompilation("task", listOf(primary), emptyList(), LogicalPath("classes")) }
        assertEquals(compilation, KtsCompilation.fromJson(compilation.toJson()))
        val longest = compilation.copy(name = "script-" + "a".repeat(128))
        assertEquals(longest, KtsCompilation.fromJson(longest.toJson()))
        assertFails { compilation.copy(name = "script-" + "a".repeat(129)) }
        assertFails { compilation.copy(primary = LogicalPath("src/other.kts")) }
        assertFails { compilation.copy(sources = listOf(primary, LogicalPath("src/other.kt"))) }
        assertEquals(2, compilation.copy(sources = listOf(primary, LogicalPath("src/imported.kts"))).sources.size)
        assertFails { ktsCompileAction(compilation, inputs(), identity, tools) }
        assertEquals(CachePolicy.LOCAL, ktsCompileAction(compilation, inputs(primary.value), identity, tools).cachePolicy)
    }
    @Test fun workspacePluginPathsOutputsAndFlagsEnterCompilationIdentity() {
        val plugin = JvmPluginApplication("example.plugin", "2.4.20", listOf(LogicalPath("plugin.jar")),
            options = listOf("message=original"), inputs = listOf(LogicalPath("data.txt")),
            pathOptions = listOf(PluginPathOption("input", PluginPathKind.INPUT_FILE, LogicalPath("data.txt")),
                PluginPathOption("output", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("generated"))),
            outputs = listOf(LogicalPath("generated")))
        val value = compilation.copy(pluginApplications = listOf(plugin))
        val tree = inputs(primary.value, "plugin.jar", "data.txt")
        val action = ktsCompileAction(value, tree, identity, tools)
        assertEquals(listOf(LogicalPath("classes"), LogicalPath("generated")), action.outputs)
        assertNotEquals(action.key(Sha256), ktsCompileAction(value.copy(pluginApplications = listOf(plugin.copy(options = listOf("message=changed")))), tree, identity, tools).key(Sha256))
        assertFails { ktsCompileAction(value, inputs(primary.value, "plugin.jar"), identity, tools) }
        assertFails { value.copy(output = LogicalPath("generated/overlap")) }
        assertFalse(portableJvmCompilation(action))
        val second = plugin.copy(id = "example.second", outputs = listOf(LogicalPath("second")),
            pathOptions = listOf(PluginPathOption("output", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("second"))))
        assertEquals(2, value.copy(pluginApplications = listOf(plugin, second)).pluginApplications.size)
        assertFails { value.copy(pluginApplications = listOf(plugin, second.copy(id = plugin.id))) }
        assertFails { value.copy(pluginApplications = listOf(plugin, plugin.copy(id = "example.second"))) }
        assertFails { value.copy(pluginApplications = List(17) { second.copy(id = "plugin$it", outputs = emptyList(), pathOptions = emptyList()) }) }
    }
    @Test fun compileDeclarationsArePinnedAndExecutionIsAlwaysFresh() {
        assertFails { compilation.copy(declaredDependencies = listOf("g:a:LATEST")) }
        assertFails { compilation.copy(declaredRepositories = listOf("http://repo.example")) }
        val tree = inputs(primary.value)
        val before = ktsCompileAction(compilation, tree, identity, tools).key(Sha256)
        assertNotEquals(before, ktsCompileAction(compilation.copy(declaredDependencies = listOf("g:a:1")), tree, identity, tools).key(Sha256))
        val bundle = LogicalPath("scripts/task/classes")
        val launch = KtsLaunch("task", bundle, listOf(bundle), listOf("@literal", "space argument"))
        assertEquals(launch, KtsLaunch.fromJson(launch.toJson()))
        val compiled = inputs("${bundle.value}/kvik-script.json", "${bundle.value}/Task.class")
        val action = ktsScriptAction(launch, compiled, identity, tools, mapOf("DECLARED" to "yes"), 1000)
        assertEquals(CachePolicy.NEVER, action.cachePolicy)
        assertEquals(launch.arguments, action.arguments)
        assertFails { ktsScriptAction(launch, compiled, identity, tools, mapOf("JAVA_TOOL_OPTIONS" to "bad"), 1000) }
        assertFails { ktsScriptAction(launch, inputs("${bundle.value}/Task.class"), identity, tools, emptyMap(), 1000) }
    }
}
