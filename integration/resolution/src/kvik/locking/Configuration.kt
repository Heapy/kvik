package kvik.locking

import kvik.core.*

/** Explicit policy: no implicit Kotlin alignment or latest-version selection. */
data class ResolverPolicy(val pins: List<Coordinate> = emptyList(), val maxComponents: Int = 256, val maxRequests: Int = 1024) {
    init {
        require(pins.map { "${it.group}:${it.name}" }.distinct().size == pins.size) { "Duplicate/conflicting resolver pins" }
        require(maxComponents in 1..1024 && maxRequests in 1..8192)
    }
    internal fun toJson(): JsonValue = obj("implementation" to "kvik-maven-v2".json(),
        "jvmCompatibility" to obj("defaultConsumerTarget" to DEFAULT_JVM_TARGET.json(), "compilerPluginHost" to COMPILER_PLUGIN_JVM_HOST.json(),
            "producerVersion" to "integer-lte-consumer-or-unspecified".json(), "ambiguity" to "reject".json()),
        "pins" to pins.sortedBy(Coordinate::toString).map { it.toString().json() }.json(),
        "maxComponents" to maxComponents.json(), "maxRequests" to maxRequests.json(),
        "maxMetadataBytes" to (1024 * 1024).json(), "maxArtifactBytes" to (64 * 1024 * 1024).json(),
        "maxAggregateBytes" to (256 * 1024 * 1024).json())
}

data class DependencyScope(val module: String, val target: String, val scope: String, val usage: String) {
    internal fun toJson(): JsonValue = obj("module" to module.json(), "target" to target.json(), "scope" to scope.json(), "usage" to usage.json())
}

data class ResolutionRequest(val coordinate: Coordinate, val variant: VariantRequest)

class EffectiveConfiguration internal constructor(
    val document: JsonValue,
    val requests: List<ResolutionRequest>,
    val repositories: List<Repository>,
    val policy: ResolverPolicy,
) {
    val digest: Digest = Sha256.digest(document.canonical().encodeToByteArray())
}

/** JVM compile/runtime closures are both locked; Native uses its published kotlin-api variant. */
fun defaultDependencyScopes(configuration: ProjectConfiguration): List<DependencyScope> = configuration.project.modules.flatMap { module ->
    module.targets.flatMap { target ->
        listOfNotNull("main", target.test?.let { "test" }).flatMap { scope ->
            (if (target.name == "jvm") listOf("java-api", "java-runtime") else listOf("kotlin-api")).map { usage ->
                DependencyScope(module.id, target.name, scope, usage)
            }
        }
    }
}

/** Scope choice and all resolver policy are part of identity, including production-only selections. */
fun effectiveConfiguration(
    configuration: ProjectConfiguration,
    policy: ResolverPolicy = ResolverPolicy(),
    scopes: List<DependencyScope> = defaultDependencyScopes(configuration),
): EffectiveConfiguration {
    // Reparse to avoid accepting a ProjectConfiguration whose document and model disagree.
    val snapshot = parseProjectConfiguration(parseJson(configuration.document.canonical()))
    require(snapshot.project == configuration.project) { "Configuration document and model disagree" }
    val pinned = policy.copy(pins = policy.pins.toList())
    require(scopes.distinct().size == scopes.size) { "Duplicate dependency scope" }
    val orderedScopes = scopes.sortedBy { it.toJson().canonical() }
    val modules = snapshot.project.modules.associateBy { it.id }
    val requests = linkedSetOf<ResolutionRequest>()
    fun visit(moduleId: String, targetName: String, scope: String, usage: String, consumerJvm: Int?, seen: MutableSet<String>) {
        val module = modules[moduleId] ?: error("Unknown dependency scope module: $moduleId")
        val target = module.targets.singleOrNull { it.name == targetName } ?: error("Unknown dependency scope target: $moduleId:$targetName")
        require(scope in setOf("main", "test")) { "Unsupported dependency scope: $scope" }
        require(usage in if (targetName == "jvm") setOf("java-api", "java-runtime") else setOf("kotlin-api")) { "Unsupported dependency usage: $targetName:$usage" }
        val source = if (scope == "main") target.main else target.test ?: error("No test source set: $moduleId:$targetName")
        if (!seen.add("$moduleId:$targetName:$scope:$usage:$consumerJvm")) return
        if (targetName == "jvm") {
            val ownTarget = jvmTargetFromOptions(target.compilerOptions)
            require(ownTarget <= consumerJvm!!) { "Module $moduleId bytecode target exceeds its JVM consumer target $consumerJvm" }
            // The dependency module is compiled at its own target, while this consuming closure
            // may select variants for a newer JVM. Both requests must be present in a scoped lock.
            if (ownTarget != consumerJvm) visit(moduleId, targetName, "main", "java-api", ownTarget, seen)
        }
        val variant = if (targetName == "jvm") jvmVariantRequest(usage, consumerJvm!!) else VariantRequest(targetName, usage, emptyMap())
        // Tests also consume main dependencies, even when their source set does not explicitly dependsOn main.
        val sources = sourceSetClosure(module, listOf(target.main, source))
        for (dependency in sources.flatMap { it.dependencies }) when (dependency) {
            is Dependency.External -> requests += ResolutionRequest(dependency.coordinate, variant)
            is Dependency.Module -> visit(dependency.module, targetName, "main", usage, consumerJvm, seen)
        }
        for (plugin in module.compilerPlugins.filter { targetName in it.targets && it.scopes.any { selected -> selected == "main" || selected == scope } }) {
            plugin.coordinate?.let { requests += ResolutionRequest(it, jvmVariantRequest("java-runtime", COMPILER_PLUGIN_JVM_HOST)) }
            plugin.module?.let { visit(it, "jvm", "main", "java-runtime", COMPILER_PLUGIN_JVM_HOST, seen) }
        }
    }
    orderedScopes.forEach { scope ->
        val target = modules[scope.module]?.targets?.singleOrNull { it.name == scope.target }
        val consumer = if (scope.target == "jvm") jvmTargetFromOptions(target?.compilerOptions ?: emptyList()) else null
        visit(scope.module, scope.target, scope.scope, scope.usage, consumer, mutableSetOf())
    }
    require(orderedScopes.size <= 8192 && requests.size <= 8192) { "Too many dependency scopes/requests" }
    val orderedRequests = requests.sortedBy { it.variant.toLockJson().canonical() + ":" + it.coordinate }
    val document = obj("schemaVersion" to CONTRACT_VERSION.json(), "project" to snapshot.document,
        "resolver" to pinned.toJson(), "scopes" to orderedScopes.map { it.toJson() }.json())
    return EffectiveConfiguration(document, orderedRequests, snapshot.project.repositories.toList(), pinned)
}

internal fun VariantRequest.toLockJson(): JsonValue = obj("platform" to platform.json(), "usage" to usage.json(),
    "attributes" to JsonValue.Object(attributes.mapValues { it.value.json() }))
