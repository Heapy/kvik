package kvik.locking

import kvik.core.*

/** Consumers with equal names remain separate conflict-resolution identities. */
sealed interface ResolutionConsumer {
    val name: String
    data class Module(override val name: String) : ResolutionConsumer
    data class Script(override val name: String) : ResolutionConsumer
}

/** Consumer conflict-resolution boundary, deliberately separate from producer variant attributes. */
data class ResolutionDomain(val consumer: ResolutionConsumer, val target: String, val compilation: String, val usage: String, val purpose: String = "dependencies") {
    constructor(module: String, target: String, compilation: String, usage: String, purpose: String = "dependencies") :
        this(ResolutionConsumer.Module(module), target, compilation, usage, purpose)
    /** Legacy module callers must not accidentally interpret a script as a workspace module. */
    val module: String get() = (consumer as? ResolutionConsumer.Module)?.name ?: error("Script domain is not a module")
    init {
        require(consumer.name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid resolution consumer name" }
        if (consumer is ResolutionConsumer.Script) {
            require(consumer.name.length <= 128 && target == "jvm" && compilation == "script") { "Scripts require a named JVM script domain" }
            require(purpose in setOf("dependencies", "compiler-plugin")) { "Unsupported script resolution purpose" }
        } else require(target in setOf("jvm", "macosArm64") && compilation in setOf("main", "test")) { "Invalid resolution domain target/compilation" }
        require(purpose in setOf("dependencies", "compiler-plugin", "ksp-runtime", "symbol-processor")) { "Invalid resolution domain purpose" }
        require(purpose !in setOf("ksp-runtime", "symbol-processor") || target == "macosArm64" && compilation == "test") { "KSP domains currently require macosArm64 tests" }
        require(usage in if (isHostTool) setOf("java-runtime") else if (target == "jvm") setOf("java-api", "java-runtime") else setOf("kotlin-api", "kotlin-runtime")) { "Invalid resolution domain usage" }
    }
    val isHostTool: Boolean get() = purpose != "dependencies"
    fun toJson(): JsonValue.Object = obj((if (consumer is ResolutionConsumer.Module) "module" else "script") to consumer.name.json(),
        "target" to target.json(), "compilation" to compilation.json(), "usage" to usage.json(), "purpose" to purpose.json())
    companion object {
        fun script(name: String, usage: String, purpose: String = "dependencies") = ResolutionDomain(ResolutionConsumer.Script(name), "jvm", "script", usage, purpose)
        fun fromJson(value: JsonValue): ResolutionDomain {
            val fields = value.fields(setOf("target", "compilation", "usage", "purpose"), setOf("module", "script"))
            require(("module" in fields) != ("script" in fields)) { "Resolution domain requires exactly one module or script" }
            val consumer = fields["module"]?.let { ResolutionConsumer.Module(it.stringValue()) }
                ?: ResolutionConsumer.Script(fields.getValue("script").stringValue())
            return ResolutionDomain(consumer, fields.getValue("target").stringValue(), fields.getValue("compilation").stringValue(), fields.getValue("usage").stringValue(), fields.getValue("purpose").stringValue())
        }
    }
}
data class ScopedPin(val domain: ResolutionDomain, val coordinate: Coordinate)

/** Scope is consumer policy; the nested rule names immutable producer evidence. */
data class ScopedMetadataExclusion(val domain: ResolutionDomain, val rule: MetadataEdgeExclusion) {
    fun toJson(): JsonValue.Object = JsonValue.Object(rule.toJson().objectValue() + ("domain" to domain.toJson()))
    fun argument(): String = toJson().canonical()
    companion object {
        fun fromJson(value: JsonValue): ScopedMetadataExclusion {
            val d = value.fields(setOf("domain", "parent", "metadata", "variant", "dependency", "reason"))
            return ScopedMetadataExclusion(ResolutionDomain.fromJson(d.getValue("domain")), MetadataEdgeExclusion.fromJson(JsonValue.Object(d - "domain")))
        }
    }
}
fun parseMetadataEdgeExclusion(text: String): ScopedMetadataExclusion {
    require(text.encodeToByteArray().size <= 4096) { "Metadata exclusion exceeds 4 KiB" }
    return ScopedMetadataExclusion.fromJson(parseJson(text))
}
internal fun canonicalExclusions(values: List<ScopedMetadataExclusion>): List<ScopedMetadataExclusion> {
    require(values.size <= 32) { "Too many metadata exclusions" }
    require(values.map { it.domain to (it.rule.parent to it.rule.dependency) }.distinct().size == values.size) { "Duplicate/conflicting metadata exclusions" }
    return values.sortedBy { it.argument() }
}

/** Fixed reviewed transfer policy; larger KSP analysis jars do not enlarge ordinary dependency domains. */
fun resolutionArtifactByteLimit(domain: ResolutionDomain): Int = (if (domain.purpose == "ksp-runtime") 128 else 64) * 1024 * 1024

/** Explicit pins; scope overrides are recorded, never inferred from another compilation. */
data class ResolverPolicy(val pins: List<Coordinate> = emptyList(), val maxComponents: Int = 256, val maxRequests: Int = 1024,
                          val scopedPins: List<ScopedPin> = emptyList(), val metadataExclusions: List<ScopedMetadataExclusion> = emptyList()) {
    init {
        canonicalExclusions(metadataExclusions)
        require(pins.map { "${it.group}:${it.name}" }.distinct().size == pins.size) { "Duplicate/conflicting resolver pins" }
        require(scopedPins.map { it.domain to "${it.coordinate.group}:${it.coordinate.name}" }.distinct().size == scopedPins.size) { "Duplicate/conflicting scoped pins" }
        require(maxComponents in 1..1024 && maxRequests in 1..8192)
    }
    fun pinsFor(domain: ResolutionDomain): List<Coordinate> = (pins.associateBy { "${it.group}:${it.name}" } +
        scopedPins.filter { it.domain == domain }.associate { "${it.coordinate.group}:${it.coordinate.name}" to it.coordinate }).values.sortedBy(Coordinate::toString)
    fun exclusionsFor(domain: ResolutionDomain): List<MetadataEdgeExclusion> = canonicalExclusions(metadataExclusions).filter { it.domain == domain }.map { it.rule }
    internal fun toJson(kspRuntime: Boolean = false): JsonValue = obj("implementation" to "kvik-maven-v3".json(),
        "jvmCompatibility" to obj("defaultConsumerTarget" to DEFAULT_JVM_TARGET.json(), "compilerPluginHost" to COMPILER_PLUGIN_JVM_HOST.json(),
            "producerVersion" to "integer-lte-consumer-or-unspecified".json(), "ambiguity" to "reject".json()),
        "pins" to pins.sortedBy(Coordinate::toString).map { it.toString().json() }.json(),
        "scopedPins" to scopedPins.sortedBy { it.domain.toJson().canonical() + it.coordinate }.map {
            obj("domain" to it.domain.toJson(), "coordinate" to it.coordinate.toString().json()) }.json(),
        "maxComponents" to maxComponents.json(), "maxRequests" to maxRequests.json(),
        "maxMetadataBytes" to (1024 * 1024).json(), "maxArtifactBytes" to (64 * 1024 * 1024).json(), "maxAggregateBytes" to (256 * 1024 * 1024).json()).let {
            val fields = it.objectValue() + (if (kspRuntime) mapOf("maxKspRuntimeArtifactBytes" to (128 * 1024 * 1024).json()) else emptyMap()) +
                (if (metadataExclusions.isEmpty()) emptyMap() else mapOf("metadataExclusions" to canonicalExclusions(metadataExclusions).map { rule -> rule.toJson() }.json()))
            JsonValue.Object(fields)
        }
}

data class DependencyScope(val module: String, val target: String, val scope: String, val usage: String) {
    internal fun toJson(): JsonValue = obj("module" to module.json(), "target" to target.json(), "scope" to scope.json(), "usage" to usage.json())
    fun domain() = ResolutionDomain(module, target, scope, usage)
}
data class ScriptDependencyScope(val script: String, val usage: String) {
    init { ResolutionDomain.script(script, usage) }
    internal fun toJson(): JsonValue = obj("script" to script.json(), "target" to "jvm".json(), "scope" to "script".json(), "usage" to usage.json())
    fun domain() = ResolutionDomain.script(script, usage)
}
fun defaultScriptDependencyScopes(configuration: ProjectConfiguration): List<ScriptDependencyScope> =
    configuration.project.scripts.keys.flatMap { name -> listOf("java-api", "java-runtime").map { ScriptDependencyScope(name, it) } }

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
                           scopes: List<DependencyScope> = defaultDependencyScopes(configuration),
                           scriptScopes: List<ScriptDependencyScope> = defaultScriptDependencyScopes(configuration)): EffectiveConfiguration {
    val snapshot = parseProjectConfiguration(parseJson(configuration.document.canonical()))
    require(snapshot.project == configuration.project) { "Configuration document and model disagree" }
    val scripts = scriptScopes.map { it.script }.distinct().associateWith { expandScript(snapshot.project, it) }
    return effectiveConfiguration(snapshot.project, "project" to snapshot.document, policy, scopes, scriptScopes, scripts)
}

/** Companion declarations retain their own document and origins; no synthetic module is published. */
fun effectiveConfiguration(configuration: StandaloneScriptConfiguration, policy: ResolverPolicy = ResolverPolicy()): EffectiveConfiguration {
    val snapshot = parseStandaloneScriptConfiguration(parseJson(configuration.document.canonical()))
    require(snapshot == configuration) { "Standalone configuration document and model disagree" }
    val scopes = listOf("java-api", "java-runtime").map { ScriptDependencyScope(snapshot.name, it) }
    // Empty workspace is used solely by shared closure traversal, never serialized as a project.
    val workspace = ProjectSpec(snapshot.name, snapshot.repositories, emptyList())
    return effectiveConfiguration(workspace, "standaloneScript" to snapshot.document, policy, emptyList(), scopes, mapOf(snapshot.name to snapshot.expanded()))
}

private fun effectiveConfiguration(project: ProjectSpec, authoring: Pair<String, JsonValue>, policy: ResolverPolicy,
                                   scopes: List<DependencyScope>, scriptScopes: List<ScriptDependencyScope>, scripts: Map<String, ExpandedScript>): EffectiveConfiguration {
    val pinned = policy.copy(pins = policy.pins.toList(), scopedPins = policy.scopedPins.toList(), metadataExclusions = canonicalExclusions(policy.metadataExclusions))
    require(scopes.distinct().size == scopes.size) { "Duplicate dependency scope" }
    val orderedScopes = scopes.sortedBy { it.toJson().canonical() }
    require(scriptScopes.distinct().size == scriptScopes.size) { "Duplicate script dependency scope" }
    val orderedScriptScopes = scriptScopes.sortedBy { it.toJson().canonical() }
    val modules = project.modules.associateBy { it.id }
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
        val closure = dependencyClosure(project, module.id, target.name, domain.compilation, if (runtime) DependencyUsage.RUNTIME else DependencyUsage.COMPILE)
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
            (domain.compilation in it.scopes || it.module == null && target.name == "macosArm64" && domain.compilation == "test" && "main" in it.scopes) }
        if (selectedPlugins.isNotEmpty()) {
            val pluginDomain = domain.copy(usage = "java-runtime", purpose = "compiler-plugin")
            domains += pluginDomain
            selectedPlugins.forEach { plugin ->
                plugin.coordinate?.let { requests += ResolutionRequest(it, jvmVariantRequest("java-runtime", COMPILER_PLUGIN_JVM_HOST), pluginDomain) }
                plugin.module?.let { producer ->
                    // Compilation uses its own target/API domain; loading uses the compiler host.
                    visit(ResolutionDomain(producer, "jvm", "main", "java-api"))
                    val runtimeClosure = dependencyClosure(project, producer, "jvm", "main", DependencyUsage.RUNTIME)
                    runtimeClosure.modules.forEach { visit(ResolutionDomain(it, "jvm", "main", "java-api")) }
                    runtimeClosure.external.forEach {
                        requests += ResolutionRequest(it, jvmVariantRequest("java-runtime", COMPILER_PLUGIN_JVM_HOST), pluginDomain)
                    }
                }
            }
        }
    }
    fun visitScript(scope: ScriptDependencyScope) {
        val script = scripts.getValue(scope.script)
        val domain = scope.domain()
        if (!domains.add(domain)) return
        val consumer = jvmTargetFromOptions(script.compilerOptions.map { it.value })
        val closure = scriptDependencyClosure(project, script, if (scope.usage == "java-runtime") DependencyUsage.RUNTIME else DependencyUsage.COMPILE)
        require(closure.modules.all { id -> jvmTargetFromOptions(modules.getValue(id).targets.single { it.name == "jvm" }.compilerOptions) <= consumer }) {
            "Module bytecode target exceeds script consumer target: ${script.name}"
        }
        val variant = jvmVariantRequest(scope.usage, consumer)
        closure.external.forEach { requests += ResolutionRequest(it, variant, domain) }
        closure.modules.forEach { visit(ResolutionDomain(it, "jvm", "main", "java-api")) }
        if (script.compilerPlugins.isNotEmpty()) {
            val pluginDomain = ResolutionDomain.script(script.name, "java-runtime", "compiler-plugin")
            domains += pluginDomain
            val host = jvmVariantRequest("java-runtime", COMPILER_PLUGIN_JVM_HOST)
            for (value in script.compilerPlugins) {
                val plugin = value.value
                plugin.coordinate?.let { requests += ResolutionRequest(it, host, pluginDomain) }
                plugin.module?.let { producer ->
                    visit(ResolutionDomain(producer, "jvm", "main", "java-api"))
                    val runtime = dependencyClosure(project, producer, "jvm", "main", DependencyUsage.RUNTIME)
                    runtime.modules.forEach { visit(ResolutionDomain(it, "jvm", "main", "java-api")) }
                    runtime.external.forEach { requests += ResolutionRequest(it, host, pluginDomain) }
                }
            }
        }
    }
    orderedScopes.forEach { visit(it.domain()) }
    orderedScriptScopes.forEach(::visitScript)
    require(pinned.metadataExclusions.all { rule -> requests.any { it.domain == rule.domain } }) { "Metadata exclusion refers to an empty or unselected resolution domain" }
    require(pinned.scopedPins.all { it.domain in domains }) { "Scoped pin refers to an unselected resolution domain" }
    require(domains.size <= 8192 && requests.size <= 8192) { "Too many dependency domains/requests" }
    val orderedRequests = requests.sortedBy { it.domain.toJson().canonical() + it.variant.toLockJson().canonical() + ":" + it.coordinate }
    val base = obj("schemaVersion" to CONTRACT_VERSION.json(), authoring,
        "resolver" to pinned.toJson(domains.any { it.purpose == "ksp-runtime" }), "scopes" to orderedScopes.map { it.toJson() }.json(),
        "domains" to domains.sortedBy { it.toJson().canonical() }.map { it.toJson() }.json())
    val document = if (orderedScriptScopes.isEmpty()) base else JsonValue.Object(base.objectValue() +
        ("scriptScopes" to orderedScriptScopes.map { it.toJson() }.json()))
    return EffectiveConfiguration(document, orderedRequests, project.repositories.toList(), pinned)
}

internal fun VariantRequest.toLockJson(): JsonValue = obj("platform" to platform.json(), "usage" to usage.json(),
    "attributes" to JsonValue.Object(attributes.mapValues { it.value.json() }))
