package kvik.locking

import kvik.core.*

/** Consumer conflict-resolution boundary, deliberately separate from producer variant attributes. */
data class ResolutionDomain(val module: String, val target: String, val compilation: String, val usage: String, val purpose: String = "dependencies") {
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid resolution domain module" }
        require(target in setOf("jvm", "macosArm64") && compilation in setOf("main", "test")) { "Invalid resolution domain target/compilation" }
        require(purpose in setOf("dependencies", "compiler-plugin", "ksp-runtime", "symbol-processor")) { "Invalid resolution domain purpose" }
        require(purpose !in setOf("ksp-runtime", "symbol-processor") || target == "macosArm64" && compilation == "test") { "KSP domains currently require macosArm64 tests" }
        require(usage in if (isHostTool) setOf("java-runtime") else if (target == "jvm") setOf("java-api", "java-runtime") else setOf("kotlin-api", "kotlin-runtime")) { "Invalid resolution domain usage" }
    }
    val isHostTool: Boolean get() = purpose != "dependencies"
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "target" to target.json(), "compilation" to compilation.json(), "usage" to usage.json(), "purpose" to purpose.json())
    companion object {
        fun fromJson(value: JsonValue): ResolutionDomain {
            val fields = value.fields(setOf("module", "target", "compilation", "usage", "purpose"))
            return ResolutionDomain(fields.getValue("module").stringValue(), fields.getValue("target").stringValue(), fields.getValue("compilation").stringValue(), fields.getValue("usage").stringValue(), fields.getValue("purpose").stringValue())
        }
    }
}
data class ScopedPin(val domain: ResolutionDomain, val coordinate: Coordinate)

/** Fixed reviewed transfer policy; larger KSP analysis jars do not enlarge ordinary dependency domains. */
fun resolutionArtifactByteLimit(domain: ResolutionDomain): Int = (if (domain.purpose == "ksp-runtime") 128 else 64) * 1024 * 1024

/** Explicit pins; scope overrides are recorded, never inferred from another compilation. */
data class ResolverPolicy(val pins: List<Coordinate> = emptyList(), val maxComponents: Int = 256, val maxRequests: Int = 1024,
                          val scopedPins: List<ScopedPin> = emptyList()) {
    init {
        require(pins.map { "${it.group}:${it.name}" }.distinct().size == pins.size) { "Duplicate/conflicting resolver pins" }
        require(scopedPins.map { it.domain to "${it.coordinate.group}:${it.coordinate.name}" }.distinct().size == scopedPins.size) { "Duplicate/conflicting scoped pins" }
        require(maxComponents in 1..1024 && maxRequests in 1..8192)
    }
    fun pinsFor(domain: ResolutionDomain): List<Coordinate> = (pins.associateBy { "${it.group}:${it.name}" } +
        scopedPins.filter { it.domain == domain }.associate { "${it.coordinate.group}:${it.coordinate.name}" to it.coordinate }).values.sortedBy(Coordinate::toString)
    internal fun toJson(kspRuntime: Boolean = false): JsonValue = obj("implementation" to "kvik-maven-v3".json(),
        "jvmCompatibility" to obj("defaultConsumerTarget" to DEFAULT_JVM_TARGET.json(), "compilerPluginHost" to COMPILER_PLUGIN_JVM_HOST.json(),
            "producerVersion" to "integer-lte-consumer-or-unspecified".json(), "ambiguity" to "reject".json()),
        "pins" to pins.sortedBy(Coordinate::toString).map { it.toString().json() }.json(),
        "scopedPins" to scopedPins.sortedBy { it.domain.toJson().canonical() + it.coordinate }.map {
            obj("domain" to it.domain.toJson(), "coordinate" to it.coordinate.toString().json()) }.json(),
        "maxComponents" to maxComponents.json(), "maxRequests" to maxRequests.json(),
        "maxMetadataBytes" to (1024 * 1024).json(), "maxArtifactBytes" to (64 * 1024 * 1024).json(), "maxAggregateBytes" to (256 * 1024 * 1024).json()).let {
            if (!kspRuntime) it else JsonValue.Object(it.objectValue() + ("maxKspRuntimeArtifactBytes" to (128 * 1024 * 1024).json()))
        }
}

data class DependencyScope(val module: String, val target: String, val scope: String, val usage: String) {
    internal fun toJson(): JsonValue = obj("module" to module.json(), "target" to target.json(), "scope" to scope.json(), "usage" to usage.json())
    fun domain() = ResolutionDomain(module, target, scope, usage)
}
data class ResolutionRequest(val coordinate: Coordinate, val variant: VariantRequest, val domain: ResolutionDomain)

class EffectiveConfiguration internal constructor(
    val document: JsonValue,
    val requests: List<ResolutionRequest>,
    val repositories: List<Repository>,
    val policy: ResolverPolicy,
) {
    val digest: Digest = Sha256.digest(document.canonical().encodeToByteArray())
    /** Configure bounded transport/CAS readers; individual domains still enforce their smaller cap. */
    val maxArtifactBytes: Int get() = requests.maxOfOrNull { resolutionArtifactByteLimit(it.domain) } ?: 64 * 1024 * 1024
}

fun defaultDependencyScopes(configuration: ProjectConfiguration): List<DependencyScope> = configuration.project.modules.flatMap { module ->
    module.targets.flatMap { target -> listOfNotNull("main", target.test?.let { "test" }).flatMap { scope ->
        (if (target.name == "jvm") listOf("java-api", "java-runtime") else listOf("kotlin-api", "kotlin-runtime")).map { usage ->
            DependencyScope(module.id, target.name, scope, usage)
        }
    } }
}

/** Each compilation owns its conflict domain. Prerequisite compiles have their own domains. */
fun effectiveConfiguration(configuration: ProjectConfiguration, policy: ResolverPolicy = ResolverPolicy(),
                           scopes: List<DependencyScope> = defaultDependencyScopes(configuration)): EffectiveConfiguration {
    val snapshot = parseProjectConfiguration(parseJson(configuration.document.canonical()))
    require(snapshot.project == configuration.project) { "Configuration document and model disagree" }
    val pinned = policy.copy(pins = policy.pins.toList(), scopedPins = policy.scopedPins.toList())
    require(scopes.distinct().size == scopes.size) { "Duplicate dependency scope" }
    val orderedScopes = scopes.sortedBy { it.toJson().canonical() }
    val modules = snapshot.project.modules.associateBy { it.id }
    val requests = linkedSetOf<ResolutionRequest>()
    val domains = linkedSetOf<ResolutionDomain>()
    fun visit(domain: ResolutionDomain) {
        if (!domains.add(domain)) return
        val module = modules[domain.module] ?: error("Unknown dependency scope module: ${domain.module}")
        val target = module.targets.singleOrNull { it.name == domain.target } ?: error("Unknown dependency scope target: $domain")
        require(domain.compilation == "main" || target.test != null) { "No test source set: $domain" }
        val consumer = if (domain.target == "jvm") jvmTargetFromOptions(target.optionsFor(domain.compilation)) else null
        if (consumer != null && domain.compilation == "test") require(jvmTargetFromOptions(target.compilerOptions) <= consumer) { "Main bytecode target exceeds test consumer target: $domain" }
        val runtime = domain.usage.endsWith("runtime")
        val closure = dependencyClosure(snapshot.project, module.id, target.name, domain.compilation, if (runtime) DependencyUsage.RUNTIME else DependencyUsage.COMPILE)
        if (consumer != null) require(closure.modules.all { id -> jvmTargetFromOptions(modules.getValue(id).targets.single { it.name == target.name }.compilerOptions) <= consumer }) { "Module bytecode target exceeds consumer target: $domain" }
        // Native producers expose kotlin-api; kotlin-runtime names only our link domain.
        val variant = if (consumer != null) jvmVariantRequest(domain.usage, consumer) else VariantRequest(target.name, "kotlin-api", emptyMap())
        closure.external.forEach { requests += ResolutionRequest(it, variant, domain) }
        val api = if (target.name == "jvm") "java-api" else "kotlin-api"
        closure.modules.forEach { visit(ResolutionDomain(it, target.name, "main", api)) }
        if (domain.compilation == "test") visit(ResolutionDomain(module.id, target.name, "main", api))
        if (domain.compilation == "test") target.nativeTestSettings?.let { settings ->
            val runtimeDomain = domain.copy(usage = "java-runtime", purpose = "ksp-runtime")
            val processorDomain = domain.copy(usage = "java-runtime", purpose = "symbol-processor")
            domains += runtimeDomain
            domains += processorDomain
            val host = jvmVariantRequest("java-runtime", COMPILER_PLUGIN_JVM_HOST)
            for (artifact in listOf("symbol-processing-api", "symbol-processing-aa-embeddable", "symbol-processing-common-deps")) {
                requests += ResolutionRequest(Coordinate("com.google.devtools.ksp", artifact, settings.kspVersion), host, runtimeDomain)
            }
            requests += ResolutionRequest(settings.processor, host, processorDomain)
        }
        val selectedPlugins = module.compilerPlugins.filter { target.name in it.targets &&
            (domain.compilation in it.scopes || target.name == "macosArm64" && domain.compilation == "test" && "main" in it.scopes) }
        if (selectedPlugins.isNotEmpty()) {
            val pluginDomain = domain.copy(usage = "java-runtime", purpose = "compiler-plugin")
            domains += pluginDomain
            selectedPlugins.forEach { plugin ->
                plugin.coordinate?.let { requests += ResolutionRequest(it, jvmVariantRequest("java-runtime", COMPILER_PLUGIN_JVM_HOST), pluginDomain) }
                plugin.module?.let { producer ->
                    // Compilation uses its own target/API domain; loading uses the compiler host.
                    visit(ResolutionDomain(producer, "jvm", "main", "java-api"))
                    val runtimeClosure = dependencyClosure(snapshot.project, producer, "jvm", "main", DependencyUsage.RUNTIME)
                    runtimeClosure.modules.forEach { visit(ResolutionDomain(it, "jvm", "main", "java-api")) }
                    runtimeClosure.external.forEach {
                        requests += ResolutionRequest(it, jvmVariantRequest("java-runtime", COMPILER_PLUGIN_JVM_HOST), pluginDomain)
                    }
                }
            }
        }
    }
    orderedScopes.forEach { visit(it.domain()) }
    require(pinned.scopedPins.all { it.domain in domains }) { "Scoped pin refers to an unselected resolution domain" }
    require(domains.size <= 8192 && requests.size <= 8192) { "Too many dependency domains/requests" }
    val orderedRequests = requests.sortedBy { it.domain.toJson().canonical() + it.variant.toLockJson().canonical() + ":" + it.coordinate }
    val document = obj("schemaVersion" to CONTRACT_VERSION.json(), "project" to snapshot.document,
        "resolver" to pinned.toJson(domains.any { it.purpose == "ksp-runtime" }), "scopes" to orderedScopes.map { it.toJson() }.json(),
        "domains" to domains.sortedBy { it.toJson().canonical() }.map { it.toJson() }.json())
    return EffectiveConfiguration(document, orderedRequests, snapshot.project.repositories.toList(), pinned)
}

internal fun VariantRequest.toLockJson(): JsonValue = obj("platform" to platform.json(), "usage" to usage.json(),
    "attributes" to JsonValue.Object(attributes.mapValues { it.value.json() }))
