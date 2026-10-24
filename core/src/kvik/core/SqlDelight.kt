package kvik.core

/** One synchronous SQLite 3.38 database, generated into the declared main source set, including shared multiplatform sources. */
data class SqlDelightSpec(
    val sourceSet: String,
    val sourceDirectory: LogicalPath,
    val packageName: String,
    val className: String,
    val version: String = "2.3.2",
) {
    init {
        require(sourceSet.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid SQLDelight source set" }
        require(packageName.split('.').all(::sqlDelightIdentifier)) { "Invalid SQLDelight package name" }
        require(sqlDelightIdentifier(className)) { "Invalid SQLDelight database class name" }
        require(version == "2.3.2") { "SQLDelight currently supports version 2.3.2" }
    }
    fun toJson(): JsonValue.Object = obj("sourceSet" to sourceSet.json(), "sourceDirectory" to sourceDirectory.value.json(),
        "packageName" to packageName.json(), "className" to className.json(), "version" to version.json())
    companion object {
        fun fromJson(value: JsonValue): SqlDelightSpec {
            val fields = value.fields(setOf("sourceSet", "sourceDirectory", "packageName", "className"), setOf("version"))
            return SqlDelightSpec(fields.getValue("sourceSet").stringValue(), LogicalPath(fields.getValue("sourceDirectory").stringValue()),
                fields.getValue("packageName").stringValue(), fields.getValue("className").stringValue(), fields["version"]?.stringValue() ?: "2.3.2")
        }
    }
}

private fun sqlDelightIdentifier(value: String): Boolean = value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && value !in setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package",
    "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while", "_")

/** Compiler tools have a separate locked host classpath; they are never consumer dependencies. */
fun sqlDelightCompilerCoordinates(version: String): List<Coordinate> {
    require(version == "2.3.2") { "Unsupported SQLDelight compiler version" }
    return listOf("app.cash.sqldelight:core:$version", "app.cash.sqldelight:compiler-env:$version",
        "app.cash.sqldelight:sqlite-3-38-dialect:$version", "app.cash.sql-psi:environment:0.7.3").map(Coordinate::parse)
}
