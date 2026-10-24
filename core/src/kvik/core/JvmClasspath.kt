package kvik.core

/** Ordered compiler classpath, with paths relative to the accompanying immutable input tree. */
data class JvmClasspath(val module: String, val compilation: String, val classpath: List<LogicalPath>) {
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid classpath module" }
        require(compilation in setOf("main", "test")) { "Invalid classpath compilation" }
        require(classpath.distinct().size == classpath.size && classpath.none { ',' in it.value }) { "Invalid classpath entries" }
    }

    /** Every entry must be a captured JAR or nonempty directory; no unrelated files may be included. */
    fun requireInputs(inputs: TreeManifest) {
        fun contains(root: LogicalPath, file: FileEntry): Boolean =
            file.path == root && root.value.endsWith(".jar") || file.path.value.startsWith("${root.value}/")
        require(classpath.all { root -> inputs.files.any { contains(root, it) } }) { "Missing classpath input" }
        require(inputs.files.all { file -> classpath.any { contains(it, file) } }) { "Input is outside the compiler classpath" }
    }

    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.json(),
        "compilation" to compilation.json(), "classpath" to classpath.map { it.value.json() }.json())

    companion object {
        fun fromJson(value: JsonValue): JvmClasspath {
            val fields = value.fields(setOf("schemaVersion", "module", "compilation", "classpath"))
            fields.requireVersion()
            return JvmClasspath(fields.getValue("module").stringValue(), fields.getValue("compilation").stringValue(),
                fields.getValue("classpath").arrayValue().map { LogicalPath(it.stringValue()) })
        }
    }
}
