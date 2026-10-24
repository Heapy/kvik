package kvik.resolution

import kvik.core.Coordinate
import kvik.core.VariantRequest

internal data class PomDependency(val coordinate: Coordinate, val transitive: Boolean)

/** Published POM dependency model only: never executes build/plugins or reads a local relativePath. */
internal data class EffectivePom(
    val packaging: String,
    val declared: List<Map<String, PomNode>>,
    val management: Map<String, Map<String, PomNode>>,
) {
    fun dependencies(request: VariantRequest): List<Coordinate> = dependencyEdges(request).map { it.coordinate }
    fun dependencyEdges(request: VariantRequest): List<PomDependency> = declared.mapNotNull { dependency ->
        val effective = (management[dependencyKey(dependency)].orEmpty() - "optional") + dependency
        val scope = effective.scalar("scope") ?: "compile"
        require(scope in setOf("compile", "runtime", "test", "provided")) { "Unsupported POM scope: $scope" }
        val optional = effective.scalar("optional")
        require(optional in setOf(null, "true", "false")) { "Unsupported POM optional value" }
        // These declarations cannot contribute any consumer transitive artifact. Their local
        // test/provided exclusions and versions do not change the published dependency closure.
        if (scope in setOf("test", "provided") || optional == "true" || scope == "runtime" && request.usage != "java-runtime") null
        else {
            require("exclusions" !in management[dependencyKey(dependency)].orEmpty()) { "Managed POM exclusion merging is unsupported" }
            val exclusions = effective["exclusions"]
            val all = exclusions?.children?.isNotEmpty() == true
            if (all) {
                val exclusion = exclusions.children.singleOrNull()
                require(exclusions.text.isEmpty() && exclusion?.name == "exclusion" && exclusion.text.isEmpty() &&
                    exclusion.children.map { it.name }.toSet() == setOf("groupId", "artifactId") && exclusion.children.size == 2 &&
                    exclusion.value("groupId") == "*" && exclusion.value("artifactId") == "*") { "Only literal wildcard-all POM exclusions are supported" }
            } else require(exclusions == null || exclusions.text.isEmpty()) { "Invalid POM exclusions" }
            PomDependency(coordinate(effective - "exclusions"), !all)
        }
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
    // Parent and BOM models share one bounded loader. Every consulted POM goes through the
    // resolver callback once, so imported metadata participates in lock identity and replay.
    val loaded = mutableMapOf(coordinate to root)
    val complete = mutableMapOf<Coordinate, EffectivePom>()
    val importing = mutableSetOf<Coordinate>()
    fun model(requested: Coordinate): PomNode = loaded.getOrPut(requested) {
        require(loaded.size < 256) { "POM parent/import model bound exceeded" }
        parent(requested)
    }
    fun build(requested: Coordinate, document: PomNode): EffectivePom {
        complete[requested]?.let { return it }
        require(importing.add(requested)) { "POM dependency-management import cycle: $requested" }
        require(importing.size <= 16) { "POM import depth exceeded" }
        try {
            return buildEffectivePom(requested, document, ::model, { imported ->
                val bom = model(imported)
                require(bom.value("packaging") == "pom") { "Imported BOM must have POM packaging" }
                build(imported, bom)
            }, importing.size).also { complete[requested] = it }
        } finally { importing.remove(requested) }
    }
    return build(coordinate, root)
}

private fun buildEffectivePom(coordinate: Coordinate, root: PomNode, parent: (Coordinate) -> PomNode,
                              imported: (Coordinate) -> EffectivePom, importDepth: Int): EffectivePom {
    val ancestry = mutableListOf<Pair<Coordinate, PomNode>>()
    val active = mutableSetOf<Coordinate>()
    fun load(expected: Coordinate, model: PomNode) {
        require(active.add(expected)) { "Parent POM ancestry cycle: $expected" }
        require(active.size + importDepth - 1 <= 16) { "Parent/import POM ancestry depth exceeded" }
        require(model.name == "project" && model.value("modelVersion") == "4.0.0") { "Unsupported POM model" }
        val allowed = setOf("modelVersion", "parent", "groupId", "artifactId", "version", "packaging", "dependencies", "dependencyManagement", "name", "description", "url", "licenses", "developers", "contributors", "scm", "organization", "issueManagement", "ciManagement", "inceptionYear", "properties", "build", "distributionManagement", "modules", "profiles", "reporting", "mailingLists", "prerequisites", "repositories")
        val unsupported = model.children.map { it.name }.filter { it !in allowed }.distinct().sorted()
        require(unsupported.isEmpty()) { "Unsupported POM fields for $expected: ${unsupported.joinToString()}" }
        model.children.forEach { model.child(it.name) } // Duplicate model fields are never merged implicitly.
        // Maven prerequisites constrain the producer build, never the published dependency closure.
        model.child("prerequisites")?.let { prerequisites ->
            require(prerequisites.children.all { it.name == "maven" }) { "Unsupported POM prerequisites for $expected" }
            prerequisites.value("maven")
        }
        model.child("repositories")?.let { validateInactiveRepositories(expected, it) }
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
    val profileDependencies = mutableListOf<PomNode>()
    for ((owner, model) in ancestry) {
        model.child("properties")?.let { props ->
            props.children.forEach { property ->
                // Some published parents repeat producer properties verbatim. Equal scalar
                // values are unambiguous; conflicting duplicates still fail closed.
                require(property.children.isEmpty() && props.children.filter { it.name == property.name }.all {
                    it.children.isEmpty() && it.text == property.text
                }) { "Invalid/conflicting POM property for $owner: ${property.name}" }
                if (property.name != "project.directory" || model === root) properties[property.name] = property.text
            }
        }
        model.child("profiles")?.let { profiles ->
            require(profiles.children.all { it.name == "profile" }) { "Invalid POM profiles" }
            for (profile in profiles.children) {
                val unsupported = profile.children.map { it.name }.filter { it !in setOf("id", "activation", "build", "reporting", "modules", "properties", "dependencies") }.distinct().sorted()
                require(unsupported.isEmpty()) { "Resolution-affecting POM profile fields unsupported: ${unsupported.joinToString()}" }
                profile.children.forEach { profile.child(it.name) }
                profile.child("properties")?.children?.forEach { profileProperties += it.name }
                profile.child("dependencies")?.let { profileDependencies += it }
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
    val managementDeclarations = expanded(management.values)
    require(managementDeclarations.size <= 10000) { "POM dependency-management entry bound exceeded" }
    // Maven inherits child/parent entries before importing BOMs. Explicit management wins
    // regardless of XML order, then the first imported definition wins among BOMs.
    val effectiveManagement = managementDeclarations.filterValues { it.scalar("scope") != "import" }.toMutableMap()
    for (fields in managementDeclarations.values.filter { it.scalar("scope") == "import" }) {
        require(fields.keys == setOf("groupId", "artifactId", "version", "type", "scope") && fields.scalar("type") == "pom") {
            "BOM import requires exactly a POM coordinate and import scope"
        }
        val bom = Coordinate(fields.scalar("groupId")!!, fields.scalar("artifactId")!!, fields.scalar("version")!!)
        require(!bom.version.lowercase().endsWith("-snapshot") && bom.version.lowercase() !in setOf("latest", "release") &&
            !bom.version.lowercase().startsWith("latest.")) { "Mutable BOM version unsupported" }
        for ((key, value) in imported(bom).management) effectiveManagement.getOrPut(key) { value }
        require(effectiveManagement.size <= 10000) { "POM dependency-management entry bound exceeded" }
    }
    val effectiveDependencies = expanded(dependencies.values)
    for (profile in profileDependencies) for (raw in declarations(profile)) {
        // A profile-local test dependency is harmless only if it cannot replace an inherited
        // or local consumer dependency. No activation condition is guessed from this host.
        require(raw.scalar("scope") == "test") { "Resolution-affecting POM profile dependency unsupported" }
        val identityFields = raw.filterKeys { it in setOf("groupId", "artifactId", "type", "classifier") }
            .mapValues { (_, field) -> field.copy(text = interpolate(field.text)) }
        val key = dependencyKey(identityFields)
        effectiveDependencies[key]?.let { existing ->
            val effective = (effectiveManagement[key].orEmpty() - "optional") + existing
            require(effective.scalar("scope") in setOf("test", "provided") || effective.scalar("optional") == "true") {
                "POM profile test dependency can override a consumer dependency: $key"
            }
        }
    }
    return EffectivePom(root.value("packaging") ?: "jar", effectiveDependencies.values.toList(), effectiveManagement)
}

/** Snapshot versions are rejected by the resolver, so literal release-disabled repositories
 * cannot supply any supported dependency or parent. Never inherit URLs from published POMs.
 * All other repository semantics remain unsupported, including profile activation and property
 * interpolation that could turn release resolution back on.
 */
private fun validateInactiveRepositories(coordinate: Coordinate, repositories: PomNode) {
    fun check(condition: Boolean, detail: String) = require(condition) { "Unsupported POM repositories for $coordinate: $detail" }
    check(repositories.text.isEmpty() && repositories.children.all { it.name == "repository" }, "expected repository elements")
    val ids = mutableSetOf<String>()
    for (repository in repositories.children) {
        val allowed = setOf("id", "name", "url", "layout", "releases", "snapshots")
        val unsupported = repository.children.map { it.name }.filter { it !in allowed }.distinct().sorted()
        check(unsupported.isEmpty(), "unknown fields ${unsupported.joinToString()}")
        repository.children.forEach { repository.child(it.name) }
        for (field in listOf("id", "name", "url", "layout")) {
            val value = repository.value(field)
            check(value == null || '$' !in value, "repository $field must be literal")
        }
        val id = repository.value("id")
        check(!id.isNullOrBlank() && ids.add(id), "missing or duplicate repository id")
        check(!repository.value("url").isNullOrBlank(), "missing repository URL")
        check(repository.value("layout") in setOf(null, "default"), "unsupported repository layout")
        for (kind in listOf("releases", "snapshots")) {
            repository.child(kind)?.let { policy ->
                check(policy.text.isEmpty() && policy.children.all { it.name in setOf("enabled", "updatePolicy", "checksumPolicy") }, "unknown $kind policy fields")
                policy.children.forEach { policy.child(it.name) }
                check(policy.value("enabled") in setOf(null, "true", "false"), "$kind enabled must be literal true or false")
                val update = policy.value("updatePolicy")
                check(update in setOf(null, "always", "daily", "never") || update?.matches(Regex("interval:[0-9]+")) == true, "unsupported $kind updatePolicy")
                check(policy.value("checksumPolicy") in setOf(null, "ignore", "warn", "fail"), "unsupported $kind checksumPolicy")
            }
        }
        check(repository.child("releases")?.value("enabled") == "false", "repository $id must explicitly disable releases")
    }
}
