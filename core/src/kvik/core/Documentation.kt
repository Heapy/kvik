package kvik.core

const val DOKKA_VERSION = "2.2.0"

private fun documentationIdentifier(value: String) {
    require(value.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid documentation identifier" }
}

/** A separate tool distribution: these jars never enter a Kotlin compilation classpath. */
data class DocumentationDistribution(val version: String, val artifacts: TreeManifest) {
    val cliPath = LogicalPath("dokka-cli-$DOKKA_VERSION.jar")
    val pluginPaths: List<LogicalPath> get() = artifacts.files.map { it.path }.filter { it != cliPath }.sortedBy { it.value }
    val identity: Digest get() = Sha256.digest(toJson())
    init {
        require(version == DOKKA_VERSION) { "Unsupported Dokka version" }
        require(artifacts.files.map { it.path.value }.toSet() == setOf("dokka-cli-$DOKKA_VERSION.jar",
            "dokka-base-$DOKKA_VERSION.jar", "analysis-kotlin-symbols-$DOKKA_VERSION.jar",
            "kotlinx-html-jvm-0.8.0.jar", "freemarker-2.3.31.jar") && artifacts.files.size == 5) { "Incomplete Dokka distribution" }
        require(artifacts.files.all { !it.executable && it.digest.sizeBytes in 1..128L * 1024 * 1024 } &&
            artifacts.files.sumOf { it.digest.sizeBytes } <= 256L * 1024 * 1024) { "Invalid Dokka artifact bounds" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "version" to version.json(), "artifacts" to artifacts.toJson())
    companion object {
        fun fromJson(value: JsonValue): DocumentationDistribution {
            val fields = value.fields(setOf("schemaVersion", "version", "artifacts"))
            fields.requireVersion()
            return DocumentationDistribution(fields.getValue("version").stringValue(), TreeManifest.fromJson(fields.getValue("artifacts")))
        }
    }
}

/** Offline generation is fixed; package lists and arbitrary plugin/network inputs are not implicit. */
data class DocumentationSettings(
    val includes: List<LogicalPath> = emptyList(),
    val documentedVisibilities: List<String> = listOf("public"),
    val reportUndocumented: Boolean = false,
    val failOnWarning: Boolean = false,
    val skipDeprecated: Boolean = false,
    val suppressInheritedMembers: Boolean = false,
    val timeoutMillis: Long = 900_000,
) {
    init {
        require(includes.size <= 32 && includes.distinct().size == includes.size)
        require(documentedVisibilities.isNotEmpty() && documentedVisibilities.distinct().size == documentedVisibilities.size &&
            documentedVisibilities.all { it in setOf("public", "protected", "internal", "private", "package") })
        require(timeoutMillis in 1..3_600_000)
    }
    fun toJson(): JsonValue.Object = obj("includes" to includes.map { it.value.json() }.json(),
        "documentedVisibilities" to documentedVisibilities.map { it.json() }.json(),
        "reportUndocumented" to reportUndocumented.json(), "failOnWarning" to failOnWarning.json(),
        "skipDeprecated" to skipDeprecated.json(), "suppressInheritedMembers" to suppressInheritedMembers.json(),
        "timeoutMillis" to timeoutMillis.json())
    companion object {
        fun fromJson(value: JsonValue): DocumentationSettings {
            val fields = value.fields(emptySet(), setOf("includes", "documentedVisibilities", "reportUndocumented", "failOnWarning",
                "skipDeprecated", "suppressInheritedMembers", "timeoutMillis"))
            return DocumentationSettings(fields["includes"]?.arrayValue()?.map { LogicalPath(it.stringValue()) } ?: emptyList(),
                fields["documentedVisibilities"]?.arrayValue()?.map(JsonValue::stringValue) ?: listOf("public"),
                fields["reportUndocumented"]?.booleanValue() ?: false, fields["failOnWarning"]?.booleanValue() ?: false,
                fields["skipDeprecated"]?.booleanValue() ?: false, fields["suppressInheritedMembers"]?.booleanValue() ?: false,
                fields["timeoutMillis"]?.longValue() ?: 900_000)
        }
    }
}

/** Each set owns its source root; parents remain separate nodes for expect/actual analysis. */
data class DocumentationSourceSet(val name: String, val platform: String, val sourceRoot: LogicalPath,
                                  val classpath: List<LogicalPath>, val dependsOn: List<String>) {
    init {
        documentationIdentifier(name)
        require(platform in setOf("common", "jvm", "native")) { "Unsupported documentation platform" }
        require(classpath.size <= 1024 && classpath.distinct().size == classpath.size)
        require(dependsOn.distinct().size == dependsOn.size)
        dependsOn.forEach(::documentationIdentifier)
    }
    fun toJson(): JsonValue.Object = obj("name" to name.json(), "platform" to platform.json(), "sourceRoot" to sourceRoot.value.json(),
        "classpath" to classpath.map { it.value.json() }.json(), "dependsOn" to dependsOn.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): DocumentationSourceSet {
            val fields = value.fields(setOf("name", "platform", "sourceRoot", "classpath", "dependsOn"))
            return DocumentationSourceSet(fields.getValue("name").stringValue(), fields.getValue("platform").stringValue(),
                LogicalPath(fields.getValue("sourceRoot").stringValue()), fields.getValue("classpath").arrayValue().map { LogicalPath(it.stringValue()) },
                fields.getValue("dependsOn").arrayValue().map(JsonValue::stringValue))
        }
    }
}

data class DocumentationSpec(val module: String, val targets: Set<String>, val sourceSets: List<DocumentationSourceSet>,
                             val settings: DocumentationSettings = DocumentationSettings()) {
    init {
        documentationIdentifier(module)
        require(targets.isNotEmpty() && targets.all { it == "jvm" || NativeTarget.find(it) != null })
        require(sourceSets.size in 1..256 && sourceSets.map { it.name }.distinct().size == sourceSets.size)
        validateGraph(sourceSets.associate { it.name to it.dependsOn.toSet() })
        val roots = sourceSets.map { it.sourceRoot.value }
        require(roots.distinct().size == roots.size && roots.none { root -> roots.any { other -> other.startsWith("$root/") } }) {
            "Documentation source roots overlap"
        }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.json(),
        "targets" to targets.sorted().map { it.json() }.json(), "sourceSets" to sourceSets.map { it.toJson() }.json(), "settings" to settings.toJson())
    companion object {
        fun fromJson(value: JsonValue): DocumentationSpec {
            val fields = value.fields(setOf("schemaVersion", "module", "targets", "sourceSets", "settings"))
            fields.requireVersion()
            val targets = fields.getValue("targets").arrayValue().map(JsonValue::stringValue)
            require(targets.distinct().size == targets.size)
            return DocumentationSpec(fields.getValue("module").stringValue(), targets.toSet(),
                fields.getValue("sourceSets").arrayValue().map(DocumentationSourceSet::fromJson), DocumentationSettings.fromJson(fields.getValue("settings")))
        }
    }
}
