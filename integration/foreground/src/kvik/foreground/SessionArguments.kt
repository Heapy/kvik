package kvik.foreground

import kvik.core.*

/** Coverage belongs to fresh test operations, including its paired exclusion flags. */
internal fun withoutTestOptions(arguments: List<String>): List<String> = buildList {
    var index = 0
    while (index < arguments.size) when (val argument = arguments[index++]) {
        "--coverage" -> Unit
        "--coverage-exclude" -> { require(index < arguments.size); index++ }
        else -> add(argument)
    }
}

/** A moving selector is retained for watching; each operation receives only its observed revision. */
internal fun pinSessionSource(arguments: List<String>, source: SourceReference?): List<String> {
    if ("--kode" !in arguments) { require(source == null); return arguments }
    requireNotNull(source) { "Source revision is not available" }
    fun value(flag: String): String {
        require(arguments.count { it == flag } == 1) { "Missing/duplicate source option: $flag" }
        return arguments.getOrNull(arguments.indexOf(flag) + 1) ?: error("Missing $flag value")
    }
    require(source.provenance.provider == "kode" && source.provenance.project == value("--source-project")) { "Source observation belongs to another provider/project" }
    require(source.root == value("--project").substringBeforeLast('/', "")) { "Source observation has a different project root" }
    require(source.provenance.revision.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) { "Source observation must contain an immutable revision" }
    value("--revision")
    return arguments.toMutableList().also { it[arguments.indexOf("--revision") + 1] = source.provenance.revision }
}

internal fun sessionOfflineArguments(arguments: List<String>): List<String> =
    if (arguments.any { it in setOf("--kode", "--remote-cache", "--offline") }) emptyList() else listOf("--offline")

internal fun sessionOfflineEnvironment(arguments: List<String>): String =
    if ("--kode" in arguments && "--offline" !in arguments) "0" else "1"
