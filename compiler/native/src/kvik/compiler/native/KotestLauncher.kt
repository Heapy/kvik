package kvik.compiler.native

/** Adapt only the pinned Kotest 6.2.5 Native template; retain the original as an action input. */
fun adaptKotestLauncher(raw: String): String {
    require(raw.length <= 1024 * 1024 && '\r' !in raw) { "Invalid/oversized Kotest launcher" }
    val header = "@file:Suppress(\"DEPRECATION\")\n\npackage io.kotest.framework.runtime\n\n"
    val boundary = "\n@Suppress(\"RemoveRedundantBackticks\")\npublic suspend fun launch() {\n\n"
    val trailer = "\n@EagerInitialization\n@OptIn(ExperimentalStdlibApi::class)\npublic val testEngineEntryPoint: Unit = runBlocking { launch() }\n"
    require(raw.startsWith(header) && raw.endsWith(trailer) && raw.split(boundary).size == 2) { "Unsupported Kotest generated launcher template" }
    val imports = raw.removePrefix(header).substringBefore(boundary).trimEnd().lines()
    val fixed = listOf("io.kotest.core.spec.SpecRef", "io.kotest.engine.launcher.invokeTestEngine", "kotlin.OptIn", "kotlin.Suppress", "kotlin.Unit", "kotlin.native.EagerInitialization", "kotlinx.coroutines.runBlocking").map { "import $it" }
    require(imports.take(fixed.size) == fixed) { "Unsupported Kotest generated imports" }
    val qualified = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"
    val aliasImport = Regex("import ([A-Za-z_`][A-Za-z0-9_.`]*) as ([A-Za-z_][A-Za-z0-9_]*_[0-9]+)")
    val aliases = imports.drop(fixed.size).map { line ->
        val match = aliasImport.matchEntire(line) ?: error("Unsupported generated spec import")
        match.groupValues[2] to match.groupValues[1].replace("`", "")
    }
    require(aliases.isNotEmpty() && aliases.map { it.first }.distinct().size == aliases.size && aliases.map { it.second }.distinct().size == aliases.size)
    val body = raw.substringAfter(boundary).removeSuffix(trailer)
    val configLine = body.substringBefore('\n')
    require(configLine == "  val config = null" || Regex("  val config = $qualified\\(\\)").matches(configLine)) { "Unsupported generated project config" }
    val specLines = body.removePrefix(configLine + "\n  val specs = listOf(\n").removeSuffix("  )\n  invokeTestEngine(specs, config)\n}\n")
    require(body == configLine + "\n  val specs = listOf(\n" + specLines + "  )\n  invokeTestEngine(specs, config)\n}\n") { "Unsupported generated engine invocation" }
    val expected = aliases.sortedBy { it.first.substringAfterLast('_').toInt() }.mapIndexed { index, (alias, fqn) ->
        require(alias.endsWith("_$index") && Regex(qualified).matches(fqn)) { "Unsupported generated spec identity" }
        "  SpecRef.Function ({ `$alias`() }, `$alias`::class, \"$fqn\"), \n"
    }.joinToString("")
    require(specLines == expected) { "Generated spec inventory differs from imports" }
    return raw.replace("import io.kotest.engine.launcher.invokeTestEngine\n", "import kvik.kotest.runtime.invokeTestEngine\n")
}
