package kvik.resolution

import kvik.core.Coordinate
import kvik.core.VariantRequest

/** Published POM dependency model only: never executes build/plugins or reads a local relativePath. */
internal data class EffectivePom(
    val packaging: String,
    val declared: List<Map<String, PomNode>>,
    val management: Map<String, Map<String, PomNode>>,
) {
    fun dependencies(request: VariantRequest): List<Coordinate> = declared.mapNotNull { dependency ->
        val effective = (management[dependencyKey(dependency)].orEmpty() - "optional") + dependency
        val scope = effective.scalar("scope") ?: "compile"
        require(scope in setOf("compile", "runtime", "test", "provided")) { "Unsupported POM scope: $scope" }
        val optional = effective.scalar("optional")
        require(optional in setOf(null, "true", "false")) { "Unsupported POM optional value" }
        // These declarations cannot contribute any consumer transitive artifact. Their local
        // test/provided exclusions and versions do not change the published dependency closure.
        if (scope in setOf("test", "provided") || optional == "true" || scope == "runtime" && request.usage != "java-runtime") null
        else coordinate(effective)
    }

    fun managementCoordinates(): List<Coordinate> = management.values.map { dependency ->
        require(dependency.scalar("scope") in setOf(null, "compile") && dependency.scalar("optional") in setOf(null, "false")) { "Unsupported platform POM management entry" }
        coordinate(dependency)
    }
}

private fun Map<String, PomNode>.scalar(name: String): String? = get(name)?.also {
    require(it.children.isEmpty()) { "Non-scalar POM dependency $name" }
}?.text
private fun dependencyKey(fields: Map<String, PomNode>, literal: Boolean = true): String {
    val group = fields.scalar("groupId") ?: error("Missing dependency group")
    val artifact = fields.scalar("artifactId") ?: error("Missing dependency artifact")
    require(!literal || !group.contains('$') && !artifact.contains('$')) { "Unresolved POM dependency identity" }
    return "$group:$artifact:${fields.scalar("type") ?: "jar"}:${fields.scalar("classifier") ?: ""}"
}
private fun coordinate(fields: Map<String, PomNode>): Coordinate {
    require(fields.scalar("type") in setOf(null, "jar") && "classifier" !in fields && "exclusions" !in fields) { "POM imports/classifiers/types/exclusions unsupported for selected dependencies" }
    return Coordinate(fields.scalar("groupId")!!, fields.scalar("artifactId")!!, fields.scalar("version") ?: error("Unversioned POM dependency unsupported"))
}

internal fun effectivePom(coordinate: Coordinate, root: PomNode, parent: (Coordinate) -> PomNode): EffectivePom {
    val ancestry = mutableListOf<Pair<Coordinate, PomNode>>()
    val active = mutableSetOf<Coordinate>()
    fun load(expected: Coordinate, model: PomNode) {
        require(active.add(expected)) { "Parent POM ancestry cycle: $expected" }
        require(active.size <= 16) { "Parent POM ancestry depth exceeded" }
        require(model.name == "project" && model.value("modelVersion") == "4.0.0") { "Unsupported POM model" }
        val allowed = setOf("modelVersion", "parent", "groupId", "artifactId", "version", "packaging", "dependencies", "dependencyManagement", "name", "description", "url", "licenses", "developers", "contributors", "scm", "organization", "issueManagement", "ciManagement", "inceptionYear", "properties", "build", "distributionManagement", "modules", "profiles", "reporting", "mailingLists")
        require(model.children.all { it.name in allowed }) { "Unsupported POM semantics (repositories or unknown model fields)" }
        model.children.forEach { model.child(it.name) } // Duplicate model fields are never merged implicitly.
        require(model.child("distributionManagement")?.child("relocation") == null) { "POM relocation unsupported" }
        val inherited = model.child("parent")?.let { p ->
            require(p.children.all { it.name in setOf("groupId", "artifactId", "version", "relativePath") }) { "Unsupported parent POM fields" }
            require(p.value("relativePath") in setOf(null, "", "../pom.xml")) { "Unsupported parent relativePath" }
            Coordinate(p.value("groupId") ?: error("Missing parent groupId"), p.value("artifactId") ?: error("Missing parent artifactId"), p.value("version") ?: error("Missing parent version"))
        }
        val identity = Coordinate(model.value("groupId") ?: inherited?.group ?: error("Missing groupId"), model.value("artifactId") ?: error("Missing artifactId"), model.value("version") ?: inherited?.version ?: error("Missing version"))
        require(identity == expected) { "POM identity mismatch: expected $expected, found $identity" }
        if (inherited != null) {
            require(!inherited.version.lowercase().endsWith("-snapshot") && !inherited.version.lowercase().startsWith("latest.")) { "Mutable parent POM version unsupported" }
            val parentModel = parent(inherited)
            require(parentModel.value("packaging") == "pom") { "Parent must have POM packaging" }
            load(inherited, parentModel)
        }
        ancestry += expected to model
    }
    load(coordinate, root)

    val properties = linkedMapOf<String, String>()
    val profileProperties = mutableSetOf<String>()
    for ((_, model) in ancestry) {
        model.child("properties")?.let { props ->
            props.children.forEach { property ->
                require(property.children.isEmpty() && props.children.count { it.name == property.name } == 1) { "Invalid/duplicate POM property" }
                if (property.name != "project.directory" || model === root) properties[property.name] = property.text
            }
        }
        model.child("profiles")?.let { profiles ->
            require(profiles.children.all { it.name == "profile" }) { "Invalid POM profiles" }
            for (profile in profiles.children) {
                require(profile.children.all { it.name in setOf("id", "activation", "build", "reporting", "modules", "properties") }) { "Resolution-affecting POM profile unsupported" }
                profile.children.forEach { profile.child(it.name) }
                profile.child("properties")?.children?.forEach { profileProperties += it.name }
            }
        }
    }
    val builtins = mapOf("project.groupId" to coordinate.group, "project.artifactId" to coordinate.name, "project.version" to coordinate.version,
        "pom.groupId" to coordinate.group, "pom.artifactId" to coordinate.name, "pom.version" to coordinate.version)
    fun interpolate(text: String, chain: Set<String> = emptySet()): String {
        require(chain.size <= 16 && text.length <= 4096) { "POM property expansion bound exceeded" }
        val result = Regex("\\$\\{([^}]+)}").replace(text) { match ->
            val key = match.groupValues[1]
            require(key !in profileProperties) { "Resolution property affected by unsupported profile: $key" }
            require(key !in chain) { "POM property cycle: $key" }
            val value = builtins[key] ?: properties[key] ?: error("Unsupported/unresolved POM property: $key")
            interpolate(value, chain + key)
        }
        require('$' !in result && result.length <= 4096) { "Unsupported POM property expression" }
        return result
    }
    fun declarations(node: PomNode?): List<Map<String, PomNode>> {
        if (node == null) return emptyList()
        require(node.children.all { it.name == "dependency" }) { "Unsupported POM dependency list" }
        val entries = node.children.map { d ->
            require(d.children.all { it.name in setOf("groupId", "artifactId", "version", "scope", "optional", "type", "classifier", "exclusions") }) { "Unsupported POM dependency semantics" }
            d.children.forEach { d.child(it.name) }
            d.children.associate { field ->
                require(field.name == "exclusions" || field.children.isEmpty()) { "Non-scalar POM dependency field" }
                field.name to field
            }
        }
        require(entries.map { dependencyKey(it, literal = false) }.distinct().size == entries.size) { "Duplicate POM dependency declarations" }
        return entries
    }
    val dependencies = linkedMapOf<String, Map<String, PomNode>>()
    val management = linkedMapOf<String, Map<String, PomNode>>()
    for ((_, model) in ancestry.asReversed()) {
        val managed = model.child("dependencyManagement")
        require(managed == null || managed.children.all { it.name == "dependencies" }) { "Unsupported dependencyManagement fields" }
        for (fields in declarations(managed?.child("dependencies"))) {
            require(fields.scalar("scope") != "import") { "POM management imports unsupported" }
            val key = dependencyKey(fields, literal = false)
            management.getOrPut(key) { fields }
        }
        for (fields in declarations(model.child("dependencies"))) {
            val key = dependencyKey(fields, literal = false)
            dependencies.getOrPut(key) { fields }
        }
    }
    fun expanded(entries: Collection<Map<String, PomNode>>): Map<String, Map<String, PomNode>> {
        val result = linkedMapOf<String, Map<String, PomNode>>()
        for (raw in entries) {
            val fields = raw.mapValues { (name, field) -> if (name == "exclusions") field else field.copy(text = interpolate(field.text)) }
            val key = dependencyKey(fields)
            require(result.put(key, fields) == null) { "Ambiguous POM dependency identities after interpolation" }
        }
        return result
    }
    val effectiveManagement = expanded(management.values)
    require(effectiveManagement.values.none { it.scalar("scope") == "import" }) { "POM management imports unsupported" }
    return EffectivePom(root.value("packaging") ?: "jar", expanded(dependencies.values).values.toList(), effectiveManagement)
}
