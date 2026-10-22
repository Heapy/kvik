package kvik.compiler.native
import kotlin.test.*
class KotestLauncherTest {
    private val raw = """
@file:Suppress("DEPRECATION")

package io.kotest.framework.runtime

import io.kotest.core.spec.SpecRef
import io.kotest.engine.launcher.invokeTestEngine
import kotlin.OptIn
import kotlin.Suppress
import kotlin.Unit
import kotlin.native.EagerInitialization
import kotlinx.coroutines.runBlocking
import com.charleskorn.kaml.json.JsonToYamlTest as JsonToYamlTest_0
import com.charleskorn.kaml.json.ScalarClassifierTest as ScalarClassifierTest_1
import com.charleskorn.kaml.json.YamlToJsonTest as YamlToJsonTest_2

@Suppress("RemoveRedundantBackticks")
public suspend fun launch() {

  val config = null
  val specs = listOf(
  SpecRef.Function ({ `JsonToYamlTest_0`() }, `JsonToYamlTest_0`::class, "com.charleskorn.kaml.json.JsonToYamlTest"), 
  SpecRef.Function ({ `ScalarClassifierTest_1`() }, `ScalarClassifierTest_1`::class, "com.charleskorn.kaml.json.ScalarClassifierTest"), 
  SpecRef.Function ({ `YamlToJsonTest_2`() }, `YamlToJsonTest_2`::class, "com.charleskorn.kaml.json.YamlToJsonTest"), 
  )
  invokeTestEngine(specs, config)
}

@EagerInitialization
@OptIn(ExperimentalStdlibApi::class)
public val testEngineEntryPoint: Unit = runBlocking { launch() }
""".trimIndent() + "\n"
    @Test fun pinnedOfficialLauncherAdaptationPreservesTheSpecInventoryAndEagerInvocation() {
        val adapted = adaptKotestLauncher(raw)
        assertEquals(raw.replace("import io.kotest.engine.launcher.invokeTestEngine", "import kvik.kotest.runtime.invokeTestEngine"), adapted)
        for (changed in listOf(raw.replace("runBlocking { launch() }", "runBlocking { launch(); launch() }"),
            raw.replace("invokeTestEngine(specs, config)", "invokeTestEngine(specs, null)"), raw.replace("import kotlin.Unit", "import kotlin.Any"),
            raw.replace("YamlToJsonTest_2`::class", "YamlToJsonTest_1`::class"), raw + "// trailing code\n")) assertFails { adaptKotestLauncher(changed) }
    }
}
