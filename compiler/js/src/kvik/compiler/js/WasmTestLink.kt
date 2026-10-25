package kvik.compiler.js

/**
 * Kotlin 2.4.20's WasmOutputGenerationPipelinePhase calls _start even with -main noCall. Retain module initialization and exported entries, but prevent
 * the generated loader from invoking the application's main implicitly. Reject an unfamiliar call shape.
 */
internal fun wasmTestLoaderWithoutMain(source: String): String {
    val marker = "exports._start"
    var occurrences = 0
    var at = source.indexOf(marker)
    while (at >= 0) {
        occurrences++
        require(occurrences <= 1) { "Repeated Kotlin/Wasm application entry call in test loader" }
        at = source.indexOf(marker, at + marker.length)
    }
    val calls = source.lineSequence().count { it == "exports._start();" }
    require(occurrences == calls && calls <= 1) { "Unsupported Kotlin/Wasm application entry call in test loader" }
    return source.lineSequence().filterNot { it == "exports._start();" }.joinToString("\n")
}
