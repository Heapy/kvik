package kvik.compiler.js

import kvik.core.*

/** A separate KLIB calls the declared public entry without changing the production compilation. */
internal fun jsApplicationBridge(entryPoint: String): String {
    ApplicationSpec(entryPoint).requireTarget("js")
    val qualified = entryPoint.split('.').joinToString(".") { "`$it`" }
    return """
        @file:OptIn(kotlin.js.ExperimentalJsExport::class)
        @file:Suppress("UNUSED_PARAMETER")
        package kvik.application.bridge
        import $qualified as applicationEntry
        import kotlin.js.*

        private fun argumentCount(): Int = js("process.argv.length - 2")
        private fun argument(index: Int): String = js("process.argv[index + 2]")
        private fun invokeEntry(entry: () -> Unit) = entry()
        private fun invokeEntry(entry: (Array<String>) -> Unit) = entry(Array(argumentCount()) { argument(it) })

        @JsExport
        public fun kvikApplicationMain() { invokeEntry(::applicationEntry) }
    """.trimIndent() + "\n"
}

internal fun jsApplicationLoader(moduleName: String): String {
    require(moduleName.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*")))
    // Both pinned 2.4.20 backends expose named ES exports.
    return "import { kvikApplicationMain } from './$moduleName.mjs';\nkvikApplicationMain();\n"
}
