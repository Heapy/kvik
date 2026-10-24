package kvik.kts.test

import kvik.core.*
import kvik.jvm.*
import java.nio.file.Files
import java.nio.file.Path

private fun KotlinScriptDiagnostic.jsonDiagnostic() = obj("code" to code.json(), "severity" to severity.json(),
    "message" to message.json(), "source" to (source?.json() ?: JsonValue.Null),
    "line" to (line?.json() ?: JsonValue.Null), "column" to (column?.json() ?: JsonValue.Null))

suspend fun main(args: Array<String>) {
    val input = parseJson(Files.readString(Path.of(args[0]))).objectValue()
    fun text(key: String) = input.getValue(key).stringValue()
    fun texts(key: String) = input[key]?.arrayValue()?.map { it.stringValue() }.orEmpty()
    fun paths(key: String) = texts(key).map { Path.of(it) }
    val output = if (text("operation") == "compile") {
        val result = KotlinScriptHost.compile(KotlinScriptCompileRequest(primary = text("primary"),
            sources = input.getValue("sources").objectValue().mapValues { it.value.stringValue() },
            imports = input["imports"]?.objectValue()?.mapValues { (_, list) -> list.arrayValue().map { it.stringValue() } }.orEmpty(),
            classpath = paths("classpath"), options = texts("options"), pluginClasspath = paths("pluginClasspath"),
            pluginOptions = texts("pluginOptions"), declaredDependencies = texts("declaredDependencies").toSet(),
            declaredRepositories = texts("declaredRepositories").toSet(), output = Path.of(text("output"))))
        obj("successful" to result.successful.json(), "diagnostics" to result.diagnostics.map { it.jsonDiagnostic() }.json())
    } else {
        val result = KotlinScriptHost.evaluate(Path.of(text("bundle")), paths("classpath"), texts("arguments"))
        obj("successful" to result.successful.json(), "diagnostics" to result.diagnostics.map { it.jsonDiagnostic() }.json())
    }
    Files.writeString(Path.of(args[1]), output.canonical())
}
