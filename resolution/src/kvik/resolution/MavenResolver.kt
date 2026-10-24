package kvik.resolution

import kvik.core.*

/** A 404 is the only transport failure that permits another metadata format or repository. */
class MissingResource(val url: String) : IllegalStateException("HTTP 404: $url")

fun interface PomParser { fun parse(bytes: ByteArray): PomNode }
data class PomNode(val name: String, val text: String, val children: List<PomNode>) {
    fun child(name: String): PomNode? = children.filter { it.name == name }.also { require(it.size <= 1) { "Duplicate POM $name" } }.singleOrNull()
    fun value(name: String): String? = child(name)?.also { require(it.children.isEmpty()) { "Non-scalar POM $name" } }?.text
}

internal fun validateHttpsUrl(url: String) {
    require(url.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?/[A-Za-z0-9_./-]*"))) { "Unsupported HTTPS URL: $url" }
    require(url.substringAfter("https://").split('/').none { it == "." || it == ".." }) { "Non-normalized HTTPS URL" }
}
private fun Coordinate.key() = "$group:$name"
private fun Coordinate.path() = "${group.replace('.', '/')}/$name/$version/$name-$version"
private fun Map<String, JsonValue>.string(key: String) = getValue(key).stringValue()
private fun Map<String, JsonValue>.items(key: String) = get(key)?.arrayValue().orEmpty()

/** Literal versions only: compatible requires pins, explicit pins for soft prefers; no implicit latest-wins. */
class MavenResolver(
    private val transport: ArtifactTransport,
    private val pomParser: PomParser,
    pins: List<Coordinate> = emptyList(),
    private val maxComponents: Int = 256,
    private val maxRequests: Int = 1024,
    private val maxArtifactBytes: Int = 64 * 1024 * 1024,
    metadataExclusions: List<MetadataEdgeExclusion> = emptyList(),
) : DependencyResolver {
    private val metadataExclusions = metadataExclusions.sortedBy { it.toJson().canonical() }
    private val pins = pins.associateBy { it.key() }
    init {
        require(metadataExclusions.size <= 32 && metadataExclusions.map { it.parent to it.dependency }.distinct().size == metadataExclusions.size) { "Duplicate/conflicting metadata exclusions" }
        require(this.pins.size == pins.size) { "Conflicting explicit pins" }
        require(maxComponents in 1..1024 && maxRequests in 1..8192)
        require(maxArtifactBytes in 1..128 * 1024 * 1024)
    }

    override fun resolve(coordinate: Coordinate, variant: VariantRequest, repositories: List<Repository>): LockedSelection =
        resolveAll(listOf(coordinate), variant, repositories).single()

    /** Resolve roots together to detect graph conflicts, retaining a closure per root in declaration order. */
    fun resolveAll(roots: List<Coordinate>, variant: VariantRequest, repositories: List<Repository>, requireAllExclusions: Boolean = false): List<LockedSelection> =
        traverse(roots, variant, repositories, trace = false, requireAllExclusions).selections

    /** Additive inspection evidence; shares selection, validation and byte verification with locks. */
    fun resolveGraph(roots: List<Coordinate>, variant: VariantRequest, repositories: List<Repository>, requireAllExclusions: Boolean = false): ResolutionGraph =
        traverse(roots, variant, repositories, trace = true, requireAllExclusions)

    private fun traverse(roots: List<Coordinate>, variant: VariantRequest, repositories: List<Repository>, trace: Boolean, requireAllExclusions: Boolean): ResolutionGraph {
        val appliedExclusions = mutableSetOf<MetadataEdgeExclusion>()
        require(roots.isNotEmpty() && roots.size <= maxComponents)
        require(variant.attributes.keys.all { variant.platform == "jvm" && it == JVM_TARGET_VERSION_ATTRIBUTE }) { "Unsupported consumer variant attributes" }
        require(variant.platform != "common" || variant.usage == "kotlin-metadata") { "Common resolution requires kotlin-metadata usage" }
        variant.attributes[JVM_TARGET_VERSION_ATTRIBUTE]?.let(::supportedJvmTarget)
        require(repositories.size in 1..32 && repositories.map { it.id }.distinct().size == repositories.size)
        repositories.forEach { validateHttpsUrl(it.url.trimEnd('/') + "/") }
        val selected = linkedMapOf<String, Coordinate>()
        val constraints = linkedMapOf<String, MutableList<String>>()
        val visited = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        val graph = linkedMapOf<String, Component>()
        val traceNodes = linkedMapOf<String, ResolutionNode>()
        val traceEdges = mutableListOf<ResolutionEdge>()
        val constraintEdges = mutableListOf<Triple<String, Coordinate, String>>()
        val capabilityProviders = mutableMapOf<String, Pair<String, String>>()
        val parentCoordinates = mutableSetOf<Coordinate>()
        val downloaded = linkedMapOf<String, ByteArray>()
        var requests = 0
        var downloadedBytes = 0L
        fun fetch(url: String): ByteArray = downloaded[url] ?: run {
            require(++requests <= maxRequests) { "Resolution request bound exceeded" }
            transport.fetch(url).also {
                downloadedBytes += it.size
                require(downloadedBytes <= 256L * 1024 * 1024) { "Resolution aggregate byte bound exceeded" }
                downloaded[url] = it
            }
        }
        fun metadata(url: String): ByteArray = fetch(url).also { require(it.size <= 1024 * 1024) { "Metadata exceeds byte bound" } }
        fun record(c: Coordinate, preferred: Boolean = false): Coordinate {
            require(!c.version.lowercase().startsWith("latest.") && !c.version.lowercase().endsWith("-snapshot")) { "Mutable Maven version unsupported" }
            val pin = pins[c.key()]
            require(!preferred || pin != null) { "Preferred dependency $c requires an explicit version pin; automatic preference selection is unsupported" }
            val result = pin?.also {
                if (preferred) requireLiteralPreference(it.version)
                else require(atLeast(it.version, c.version)) { "Pin $it conflicts with requirement $c" }
            } ?: c
            val prior = selected[result.key()]
            if (prior == null) selected[result.key()] = result
            require(prior == null || prior == result) { "Conflicting versions: $prior and $result; supply an explicit compatible pin" }
            require(selected.size + parentCoordinates.size <= maxComponents) { "Resolution component/parent bound exceeded" }
            return result
        }
        fun constrain(source: String, c: Coordinate, semantics: String = "constraint") {
            if (semantics != "prefers") constraints.getOrPut(c.key()) { mutableListOf() } += c.version
            if (trace) {
                require(traceEdges.size + constraintEdges.size < 8192) { "Query graph edge bound exceeded" }
                constraintEdges += Triple(source, c, semantics)
            }
        }
        fun visit(input: Coordinate, platform: Boolean = false, repository: Repository? = null, expectedOwner: Coordinate? = null, transitive: Boolean = true, preferred: Boolean = false): String {
            val c = record(input, preferred)
            // Expansion belongs to an incoming edge, not a component-wide suppression flag.
            // Keep separate closure nodes; both still identify the same capability provider.
            val key = "$c|$platform|$transitive"
            require(key !in active) { "Dependency/redirect cycle: $c" }
            if (key in visited) {
                require(expectedOwner == null || graph.getValue(key).owner == expectedOwner) { "Conflicting redirect component ownership" }
                require(repository == null || graph.getValue(key).metadata.first().url.startsWith(repository.url.trimEnd('/') + "/")) { "Conflicting redirect repositories" }
                return key
            }
            active += key
            val found = (repository?.let { listOf(it) } ?: repositories).firstNotNullOfOrNull { repository ->
                val base = repository.url.trimEnd('/') + "/" + c.path()
                try { Source(repository, "$base.module", metadata("$base.module"), true) }
                catch (_: MissingResource) {
                    try { Source(repository, "$base.pom", metadata("$base.pom"), false) }
                    catch (_: MissingResource) { null }
                }
            } ?: error("Missing metadata for $c")
            val used = mutableListOf(MetadataIdentity(found.url, Sha256.digest(found.bytes)))
            val rules = metadataExclusions.filter { it.parent.key() == c.key() }
            require(rules.all { it.parent == c && found.module && it.metadata == used.single().digest }) { "Stale metadata exclusion parent version/digest/format: $c" }
            val edges = mutableListOf<String>()
            val artifacts = mutableListOf<LockedArtifact>()
            var metadataOwner = c
            var selectedVariant = ""
            var provided = listOf(c)
            var provider = "$c|$platform"
            fun dependencyEdge(input: Coordinate, dependencyPlatform: Boolean = false, dependencyRepository: Repository? = null,
                               owner: Coordinate? = null, expand: Boolean = true, kind: String = "dependency", semantics: String = "requires"): String {
                val target = visit(input, dependencyPlatform, dependencyRepository, owner, expand, preferred = semantics == "prefers")
                if (trace) {
                    require(traceEdges.size + constraintEdges.size < 8192) { "Query graph edge bound exceeded" }
                    traceEdges += ResolutionEdge(key, target, kind, input, semantics, traceNodes.getValue(target).coordinate, expand)
                }
                return target
            }
            if (found.module) {
                val module = parseJson(found.bytes.decodeToString(throwOnInvalidSequence = true)).fields(setOf("formatVersion", "component", "variants"), setOf("createdBy"))
                require(module.string("formatVersion") == "1.1") { "Unsupported Gradle metadata version" }
                val identity = module.getValue("component").fields(setOf("group", "module", "version"), setOf("attributes", "url"))
                val componentAttributes = identity["attributes"]?.objectValue().orEmpty()
                require(componentAttributes.keys.all { it == "org.gradle.status" } && componentAttributes["org.gradle.status"] in setOf(null, "release".json())) { "Unsupported component attributes/status" }
                val owner = Coordinate(identity.string("group"), identity.string("module"), identity.string("version"))
                metadataOwner = owner
                require(expectedOwner == null || owner == expectedOwner) { "Redirect component ownership mismatch" }
                if ("url" in identity) {
                    require(owner.group == c.group && owner.version == c.version && identity.string("url") == "../../${owner.name}/${owner.version}/${owner.name}-${owner.version}.module") { "Invalid component ownership URL" }
                } else require(owner == c) { "Metadata identity mismatch: $c" }
                // A normal coordinate requests its default capability. Feature variants with
                // matching platform/usage but different capabilities are not alternatives to it.
                val candidates = module.getValue("variants").arrayValue().map { it.objectValue() }.filter {
                    c in capabilities(it, c) && matches(it, variant, platform)
                }
                require(candidates.size == 1) { "Unsupported or ambiguous variant for $c ($variant): ${candidates.map { it["name"] }}" }
                val chosen = JsonValue.Object(candidates.single()).fields(setOf("name", "attributes"), setOf("dependencies", "dependencyConstraints", "files", "available-at", "capabilities"))
                provided = capabilities(chosen, c)
                val name = chosen.string("name")
                selectedVariant = name
                require(rules.all { it.variant == name }) { "Stale metadata exclusion variant: $c:$name" }
                val declaredDependencies = if (transitive || rules.isNotEmpty()) chosen.items("dependencies").map { dependency(it) } else emptyList()
                require(rules.all { rule -> declaredDependencies.count { it.coordinate == rule.dependency && !it.platform } == 1 } &&
                    (rules.isEmpty() || !platform && chosen["available-at"] == null)) { "Stale or unsupported metadata exclusion edge: $c" }
                val redirect = chosen["available-at"]
                if (redirect != null) {
                    require(chosen.items("files").isEmpty() && chosen.items("dependencies").isEmpty() && chosen.items("dependencyConstraints").isEmpty()) { "Redirect with local contents" }
                    val r = redirect.fields(setOf("url", "group", "module", "version"))
                    val target = Coordinate(r.string("group"), r.string("module"), r.string("version"))
                    require(target.group == c.group && r.string("url") == "../../${target.name}/${target.version}/${target.name}-${target.version}.module") { "Unsupported available-at URL" }
                    edges += dependencyEdge(target, platform, found.repository, owner, transitive, "redirect", "redirect")
                    provider = graph.getValue(edges.single()).capabilityProvider
                } else {
                    require(variant.platform != "common" || platform || chosen.items("files").isNotEmpty()) { "Common metadata variant has no metadata artifact" }
                    if (transitive) chosen.items("dependencyConstraints").forEach {
                        val declared = dependency(it, constraint = true)
                        constrain(key, declared.coordinate, if (declared.preferred) "prefers" else "constraint")
                    }
                    for (f in chosen.items("files")) {
                        val file = f.fields(setOf("name", "url", "size", "sha256"), setOf("sha512", "sha1", "md5"))
                        val path = file.string("url")
                        require(path.matches(Regex("[A-Za-z0-9_.-]+")) && path !in setOf(".", "..")) { "Unsupported artifact URL" }
                        require(variant.platform != "common" || path.endsWith(".jar")) { "Common metadata requires a metadata JAR" }
                        val url = found.url.substringBeforeLast('/') + "/" + path
                        val expected = Digest(file.string("sha256"), file.getValue("size").longValue())
                        require(expected.sizeBytes <= maxArtifactBytes) { "Artifact exceeds byte bound" }
                        require(Sha256.digest(fetch(url)) == expected) { "Artifact digest mismatch: $url" }
                        artifacts += LockedArtifact(c, name, found.repository.id, url, expected)
                    }
                    if (transitive) declaredDependencies.forEach { declared ->
                        val d = declared.coordinate
                        val exclusion = rules.singleOrNull { it.dependency == d }
                        if (exclusion == null) edges += dependencyEdge(d, declared.platform, semantics = if (declared.preferred) "prefers" else "requires")
                        else {
                            appliedExclusions += exclusion
                            if (trace) {
                                require(traceEdges.size + constraintEdges.size < 8192) { "Query graph edge bound exceeded" }
                                traceEdges += ResolutionEdge(key, null, "excluded", d, "explicit-exclusion", null, false, exclusion)
                            }
                        }
                    }
                }
            } else {
                val pom = effectivePom(c, pomParser.parse(found.bytes)) { parent ->
                    parentCoordinates += parent
                    require(parentCoordinates.size + selected.size <= maxComponents) { "Resolution component/parent bound exceeded" }
                    val source = repositories.firstNotNullOfOrNull { candidate ->
                        val url = candidate.url.trimEnd('/') + "/" + parent.path() + ".pom"
                        try { Source(candidate, url, metadata(url), false) } catch (_: MissingResource) { null }
                    } ?: error("Missing parent POM: $parent")
                    used += MetadataIdentity(source.url, Sha256.digest(source.bytes))
                    pomParser.parse(source.bytes)
                }
                val packaging = pom.packaging
                selectedVariant = "maven-$packaging-${variant.usage}"
                require(packaging == if (platform) "pom" else "jar") { "Unsupported POM packaging/platform: $packaging" }
                if (platform && transitive) pom.managementCoordinates().forEach { constrain(key, it) }
                if (!platform) {
                    // A POM has no producer bytecode version. The known consumer target policy is
                    // retained in the lock, but does not establish a bytecode guarantee for POM jars.
                    require(variant.platform == "jvm" && variant.usage in setOf("java-api", "java-runtime")) { "POM has no matching Kotlin ${variant.platform} variant" }
                    val url = found.url.removeSuffix(".pom") + ".jar"
                    val bytes = fetch(url)
                    require(bytes.size <= maxArtifactBytes) { "Artifact exceeds byte bound" }
                    artifacts += LockedArtifact(c, "maven-$packaging-${variant.usage}", found.repository.id, url, Sha256.digest(bytes))
                }
                if (transitive) pom.dependencyEdges(variant).forEach { edges += dependencyEdge(it.coordinate, expand = it.transitive, semantics = "pom-effective") }
            }
            // No capability conflict-resolution policy is implied by a version pin. Redirect
            // aliases share their terminal provider; independent providers must not coexist.
            for (capability in provided) {
                val prior = capabilityProviders[capability.key()]
                require(prior == null || prior == (provider to capability.version)) { "Conflicting capability ${capability.key()}: $prior and $provider:${capability.version}; capability substitution is unsupported" }
                capabilityProviders[capability.key()] = provider to capability.version
            }
            graph[key] = Component(artifacts, used, edges, metadataOwner, provider)
            if (trace) {
                require(traceNodes.size < 1024) { "Query graph node bound exceeded" }
                traceNodes[key] = ResolutionNode(key, c, platform, transitive, selectedVariant, found.repository.id, used.toList(), artifacts.toList())
            }
            active -= key
            visited += key
            return key
        }
        val rootKeys = roots.map { visit(it) }
        require(!requireAllExclusions || appliedExclusions == metadataExclusions.toSet()) { "Metadata exclusion was not applied in its full resolution domain" }
        constraints.forEach { (key, versions) -> selected[key]?.let { c -> versions.forEach { require(atLeast(c.version, it)) { "Constraint $key:$it conflicts with $c" } } } }
        val selections = roots.zip(rootKeys).map { (root, key) ->
            val emitted = mutableSetOf<String>()
            val artifacts = linkedMapOf<String, LockedArtifact>()
            val metadata = linkedMapOf<String, MetadataIdentity>()
            fun collect(key: String) {
                if (!emitted.add(key)) return
                val component = graph.getValue(key)
                component.artifacts.forEach { artifacts[it.url] = it }
                component.metadata.forEach { metadata[it.url] = it }
                component.edges.forEach(::collect)
            }
            collect(key)
            LockedSelection(root, variant, artifacts.values.toList(), metadata.values.toList())
        }
        for ((source, requirement, semantics) in constraintEdges) {
            val chosen = selected[requirement.key()]
            val target = traceNodes.values.firstOrNull { it.coordinate == chosen }?.id
            traceEdges += ResolutionEdge(source, target, "constraint", requirement, semantics, chosen, false)
        }
        return ResolutionGraph(variant, roots.zip(rootKeys).map { (root, key) -> ResolutionRoot(root, key) }, traceNodes.values.toList(), traceEdges.toList(), selections)
    }

    private data class Source(val repository: Repository, val url: String, val bytes: ByteArray, val module: Boolean)
    private data class Component(val artifacts: List<LockedArtifact>, val metadata: List<MetadataIdentity>, val edges: List<String>, val owner: Coordinate, val capabilityProvider: String)
}

/** Only literal provided capabilities are modeled. Requested capability selectors remain unsupported. */
private fun capabilities(variant: Map<String, JsonValue>, coordinate: Coordinate): List<Coordinate> {
    val declared = variant["capabilities"]?.arrayValue().orEmpty()
    require(declared.size <= 32) { "Too many variant capabilities" }
    if (declared.isEmpty()) return listOf(coordinate)
    return declared.map { value ->
        val fields = value.fields(setOf("group", "name", "version"))
        Coordinate(fields.string("group"), fields.string("name"), fields.string("version"))
    }.also { values ->
        require(values.map { it.key() }.distinct().size == values.size) { "Duplicate/conflicting variant capabilities" }
    }
}

private fun atLeast(selected: String, required: String): Boolean {
    if (selected == required) return true
    require(selected.matches(Regex("[0-9]+(\\.[0-9]+)*")) && required.matches(Regex("[0-9]+(\\.[0-9]+)*"))) { "Unsupported version ordering: $selected / $required" }
    val a = selected.split('.').map { it.toLong() }; val b = required.split('.').map { it.toLong() }
    for (i in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(i) { 0L }).compareTo(b.getOrElse(i) { 0L })
        if (comparison != 0) return comparison > 0
    }
    return true
}

private data class ModuleDependency(val coordinate: Coordinate, val platform: Boolean, val preferred: Boolean)

private fun requireLiteralPreference(version: String) {
    require(version.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")) &&
        !version.lowercase().startsWith("latest.") && !version.lowercase().endsWith("-snapshot")) { "Unsupported preferred version: $version; only immutable literals are supported" }
}

private fun dependency(value: JsonValue, constraint: Boolean = false): ModuleDependency {
    val d = value.fields(setOf("group", "module", "version"), if (constraint) setOf("reason") else setOf("attributes", "endorseStrictVersions", "reason"))
    val version = d.getValue("version").fields(emptySet(), setOf("requires", "prefers"))
    require(version.size == 1) { "Unsupported rich version combination: require exactly one literal requires or prefers" }
    val preferred = "prefers" in version
    val literal = version.string(if (preferred) "prefers" else "requires")
    if (preferred) requireLiteralPreference(literal)
    val attributes = d["attributes"]?.objectValue().orEmpty()
    require(attributes.isEmpty() || attributes == mapOf("org.gradle.category" to "platform".json())) { "Unsupported dependency attributes" }
    val platform = attributes.isNotEmpty()
    require(d["endorseStrictVersions"] == null || platform && d.getValue("endorseStrictVersions").booleanValue()) { "Unsupported strict-version endorsement" }
    return ModuleDependency(Coordinate(d.string("group"), d.string("module"), literal), platform, preferred)
}

private fun matches(v: Map<String, JsonValue>, request: VariantRequest, platform: Boolean): Boolean {
    val a = v["attributes"]?.objectValue() ?: error("Variant missing attributes")
    fun attribute(key: String) = a[key]?.stringValue()
    if (attribute("org.gradle.category") != if (platform) "platform" else "library") return false
    val type = when (request.platform) { "macosArm64" -> "native"; else -> request.platform }
    if (attribute("org.gradle.usage") != request.usage) return false
    if (!platform && attribute("org.jetbrains.kotlin.platform.type") != type) {
        // Java publications need not carry Kotlin's platform attribute. Require the explicit Java
        // usage and jar shape; this rule never turns a Native request into a JVM selection.
        if (type != "jvm" || attribute("org.jetbrains.kotlin.platform.type") != null ||
            request.usage !in setOf("java-api", "java-runtime") || attribute("org.gradle.libraryelements") != "jar") return false
    }
    if (request.platform == "macosArm64" && attribute("org.jetbrains.kotlin.native.target") !in setOf(null, "macos_arm64")) return false
    if (request.platform == "macosArm64" && attribute("org.jetbrains.kotlin.native.target") == null) {
        require(v.items("files").isEmpty() && v.items("dependencies").isEmpty() && v["available-at"] == null) { "Native binary variant lacks target" }
    }
    val supported = setOf("artifactType", "org.gradle.category", "org.gradle.usage", "org.gradle.jvm.environment", "org.gradle.libraryelements", "org.gradle.dependency.bundling", "org.jetbrains.kotlin.platform.type", "org.jetbrains.kotlin.native.target", "org.jetbrains.kotlin.klib.packaging") +
        if (type == "jvm") setOf(JVM_TARGET_VERSION_ATTRIBUTE) else emptySet()
    require(a.keys.all { it in supported }) { "Unsupported variant attributes: ${a.keys - supported}" }
    require(attribute("org.gradle.jvm.environment") in setOf(null, if (type == "jvm") "standard-jvm" else "non-jvm")) { "Unsupported JVM environment" }
    require(attribute("org.gradle.libraryelements") in setOf(null, if (type in setOf("jvm", "common")) "jar" else "klib")) { "Unsupported library elements" }
    require(attribute("org.jetbrains.kotlin.klib.packaging") in setOf(null, "packed")) { "Unsupported klib packaging" }
    require(attribute("artifactType") in setOf(null, if (type == "native") "org.jetbrains.kotlin.klib" else "jar")) { "Unsupported artifact type" }
    require(attribute("org.gradle.dependency.bundling") in setOf(null, "external")) { "Unsupported bundling" }
    if (type in setOf("jvm", "common")) require(attribute("org.jetbrains.kotlin.native.target") == null && attribute("org.jetbrains.kotlin.klib.packaging") == null) { "Native-only attributes on a $type variant" }
    a[JVM_TARGET_VERSION_ATTRIBUTE]?.let { value ->
        val producer = value.longValue()
        require(producer in 1..Int.MAX_VALUE.toLong()) { "Invalid producer JVM version" }
        val consumer = request.attributes[JVM_TARGET_VERSION_ATTRIBUTE]?.let(::supportedJvmTarget)
            ?: error("Versioned JVM metadata requires an explicit consumer target")
        if (producer > consumer) return false
    }
    return true
}

/** Offline replay performs no metadata resolution; every byte must match the immutable lock. */
fun verifyLocked(selection: LockedSelection, blobs: (Digest) -> ByteArray?): List<ByteArray> {
    fun read(digest: Digest): ByteArray {
        val bytes = blobs(digest) ?: error("Missing locked blob: ${digest.hash}")
        require(Sha256.digest(bytes) == digest) { "Corrupt locked blob: ${digest.hash}" }
        return bytes
    }
    selection.metadata.forEach { read(it.digest) }
    return selection.artifacts.map { read(it.digest) }
}
