@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.locking.ResolutionDomain
import kvik.locking.ResolutionConsumer
import kvik.locking.ResolverPolicy
import kvik.locking.ScopedPin
import kvik.locking.ScopedMetadataExclusion
import kvik.locking.parseMetadataEdgeExclusion
import kotlinx.cinterop.*
import platform.posix.*

internal data class ProjectFile(val root: String, val name: LogicalPath, val bytes: ByteArray, val configuration: ProjectConfiguration) {
    val path: String get() = "$root/${name.value}"
}

internal fun currentDirectory(): String = memScoped {
        val buffer = allocArray<ByteVar>(4096)
        getcwd(buffer, 4096u)?.toKString() ?: error("Cannot read working directory: errno=$errno")
}

internal fun validatedPluginConfiguration(configuration: ProjectConfiguration): ProjectConfiguration = configuration.also {
    validatePluginSettings(buildPlugins, it.pluginSettings)
}

internal fun parseBuildProjectConfiguration(bytes: ByteArray): ProjectConfiguration =
    validatedPluginConfiguration(parseProjectConfiguration(parseJson(bytes.decodeToString(throwOnInvalidSequence = true))))

internal fun readProject(projectPath: String, parse: (ByteArray) -> ProjectConfiguration = {
    parseProjectConfiguration(parseJson(it.decodeToString(throwOnInvalidSequence = true)))
}, expandScriptDeclarations: Boolean = true): ProjectFile {
    val absolute = if (projectPath.startsWith('/')) projectPath else "${currentDirectory()}/$projectPath"
    NativeFiles.validateAbsolute(absolute)
    val parent = absolute.substringBeforeLast('/').ifEmpty { "/" }
    val resolved = realpath(parent, null) ?: error("Cannot resolve project directory: errno=$errno")
    val root = try { resolved.toKString() } finally { free(resolved) }
    require(root != "/") { "Filesystem root cannot be a project" }
    NativeFiles.ensureDirectory(root, create = false)
    val name = LogicalPath(absolute.substringAfterLast('/'))
    val bytes = NativeFiles.read("$root/${name.value}", 4 * 1024 * 1024)
    val configuration = validatedPluginConfiguration(parse(bytes))
    return ProjectFile(root, name, bytes, if (expandScriptDeclarations)
        configuration.withScriptDeclarations(localScriptSourceReader(root)) else configuration)
}

/** Bounded preflight reads reject links in every ancestor as well as the leaf. */
internal fun localScriptSourceReader(root: String): (LogicalPath) -> ByteArray = boundedScriptSourceReader { path ->
    NativeFiles.ensureDirectory(root, create = false)
    var current = root
    for (part in path.value.split('/').dropLast(1)) {
        current += "/$part"
        NativeFiles.ensureDirectory(current, create = false)
    }
    NativeFiles.read("$root/${path.value}", 4 * 1024 * 1024)
}

/** Shared reads stay bounded across all named scripts, including duplicate imports. */
internal fun boundedScriptSourceReader(read: (LogicalPath) -> ByteArray): (LogicalPath) -> ByteArray {
    val observed = mutableMapOf<LogicalPath, ByteArray>()
    var total = 0L
    return { path -> observed.getOrPut(path) {
        require(observed.size < 20_000) { "Too many script declaration inputs" }
        val bytes = read(path)
        require(bytes.size <= 4 * 1024 * 1024 && total + bytes.size <= 256L * 1024 * 1024) { "Script declaration input byte limit exceeded" }
        total += bytes.size
        bytes
    } }
}

internal fun projectPath(root: String, value: String): String = (if (value.startsWith('/')) value else "$root/${LogicalPath(value).value}").also {
    NativeFiles.validateAbsolute(it)
}

internal data class ProjectOptions(
    val project: String = "kvik.json",
    val store: String = ".kvik/cache",
    val lock: String = "kvik.lock.json",
    val pins: List<Coordinate> = emptyList(),
    val offline: Boolean = false,
    val scopedPins: List<ScopedPin> = emptyList(),
    val metadataExclusions: List<ScopedMetadataExclusion> = emptyList(),
)

internal fun ProjectOptions.resolverPolicy() = ResolverPolicy(pins, scopedPins = scopedPins, metadataExclusions = metadataExclusions)

internal fun ScopedPin.argument(): String = with(domain) {
    val prefix = when (val owner = consumer) {
        is ResolutionConsumer.Module -> owner.name
        is ResolutionConsumer.Script -> "script:${owner.name}"
    }
    "$prefix:$target:$compilation:$usage:$purpose=$coordinate"
}

private fun scopedPin(value: String): ScopedPin {
    require(value.count { it == '=' } == 1) {
        "Expected scoped pin MODULE:TARGET:COMPILATION:USAGE:PURPOSE=GROUP:ARTIFACT:VERSION"
    }
    val domain = value.substringBefore('=').split(':')
    require(domain.all { it.isNotEmpty() }) { "Scoped pin has an empty domain component" }
    val selected = when {
        domain.size == 5 -> ResolutionDomain(domain[0], domain[1], domain[2], domain[3], domain[4])
        domain.size == 6 && domain[0] == "script" -> ResolutionDomain(ResolutionConsumer.Script(domain[1]), domain[2], domain[3], domain[4], domain[5])
        else -> error("Scoped pin requires MODULE:TARGET:COMPILATION:USAGE:PURPOSE or script:NAME:jvm:script:USAGE:PURPOSE")
    }
    return ScopedPin(selected, Coordinate.parse(value.substringAfter('=')))
}

internal fun projectOptions(args: List<String>, lockOptions: Boolean): ProjectOptions {
    val values = mutableMapOf<String, String>()
    val pins = mutableListOf<Coordinate>()
    val scopedPins = mutableListOf<ScopedPin>()
    val metadataExclusions = mutableListOf<ScopedMetadataExclusion>()
    var offline = false
    var index = 0
    val allowed = setOf("--project", "--pin", "--scope-pin", "--exclude-metadata-edge") + if (lockOptions) setOf("--store", "--lock") else emptySet()
    while (index < args.size) {
        val flag = args[index++]
        if (flag == "--offline" && lockOptions) {
            require(!offline) { "Duplicate option: --offline" }
            offline = true
            continue
        }
        require(flag in allowed) { "Unsupported option: $flag" }
        val value = args.getOrNull(index++)
        require(value != null && value.isNotBlank() && !value.startsWith("--")) { "Missing value for $flag" }
        if (flag == "--pin") pins += Coordinate.parse(value)
        else if (flag == "--scope-pin") scopedPins += scopedPin(value)
        else if (flag == "--exclude-metadata-edge") {
            require(metadataExclusions.size < 32) { "At most 32 metadata edge exclusions are supported" }
            metadataExclusions += parseMetadataEdgeExclusion(value)
        }
        else require(values.put(flag, value) == null) { "Duplicate option: $flag" }
    }
    return ProjectOptions(values["--project"] ?: "kvik.json", values["--store"] ?: ".kvik/cache", values["--lock"] ?: "kvik.lock.json", pins,
        offline || getenv("KVIK_BOOTSTRAP_OFFLINE")?.toKString() == "1", scopedPins, metadataExclusions)
}
