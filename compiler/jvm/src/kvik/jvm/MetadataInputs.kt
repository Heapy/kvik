package kvik.jvm

import kvik.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.LocalDateTime
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val METADATA_LIMIT = 64 * 1024 * 1024
private const val PSM_PATH = "META-INF/kotlin-project-structure-metadata.json"

/** Prepare portable editor libraries without loading BTA or compiling project sources. */
internal fun prepareMetadata(workspace: Workspace, request: WorkerRequest): WorkerResult {
    require(request.operation == "metadata-prepare")
    require(request.classpath.distinct().size == request.classpath.size && request.classpath.size <= 256) { "Duplicate or too many metadata inputs" }
    val destination = workspace.path(request.outputDirectory, false)
    if (Files.exists(destination)) reject("KVIK_OUTPUT_EXISTS", "Output directory must be new")
    val staging = Files.createTempDirectory(destination.parent, ".kvik-metadata-output-")
    val scratch = Files.createTempDirectory(workspace.root, ".kvik-metadata-inputs-")
    try {
        val libraries = metadataClasspath(workspace, request.classpath, scratch, request.metadataTargets,
            nativeStdlibPath = request.classpath.singleOrNull { it == "input/native-stdlib/shared" })
        require(libraries.size in 1..4096) { "Too many prepared metadata libraries" }
        val names = libraries.mapIndexed { index, library ->
            val name = "$index.klib"
            val output = staging.resolve(name)
            if (Files.isDirectory(library)) packMetadataLibrary(library, output) else Files.copy(library, output)
            name
        }
        Files.writeString(staging.resolve("classpath.json"), obj("schemaVersion" to CONTRACT_VERSION.json(),
            "classpath" to names.map { it.json() }.json()).canonical() + "\n", CREATE_NEW, WRITE)
        Files.move(staging, destination, ATOMIC_MOVE)
        return WorkerResult(request.requestId, WorkerStatus.SUCCESS, 0, emptyList())
    } finally { workspace.removeTree(staging); workspace.removeTree(scratch) }
}

/** Fixed timestamps, ordering and stored entries make relocated editor KLIBs byte-identical. */
private fun packMetadataLibrary(directory: Path, output: Path) {
    val files = Files.walk(directory).use { paths -> paths.filter { !Files.isDirectory(it) }.limit(10001).toList() }
    require(files.size in 1..10000 && files.all { !Files.isSymbolicLink(it) && Files.isRegularFile(it) }) { "Invalid metadata tree" }
    require(files.sumOf { Files.size(it) } <= METADATA_LIMIT) { "Expanded metadata library exceeds 64 MiB" }
    ZipOutputStream(Files.newOutputStream(output, CREATE_NEW, WRITE)).use { zip ->
        for (file in files.sortedBy { directory.relativize(it).toString() }) {
            val name = LogicalPath(directory.relativize(file).toString()).value
            val bytes = Files.readAllBytes(file)
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
                setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
            }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        }
    }
}

/** Read only declared metadata; no target KLIB substitution, repositories or ambient classpath. */
internal fun metadataClasspath(workspace: Workspace, paths: List<String>, scratch: Path, targets: Set<String>, minimumTargets: Int = 2, nativeStdlibPath: String? = null): List<Path> {
    require(targets.size >= minimumTargets && targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null })
    return paths.flatMapIndexed { index, logical ->
        val path = workspace.path(logical)
        if (Files.isDirectory(path)) {
            val directory = workspace.classpath(logical) // Reject links/special children recursively.
            val manifest = directory.resolve("default/manifest")
            require(Files.isRegularFile(manifest) && Files.size(manifest) <= 1024 * 1024) { "Invalid/oversized metadata manifest" }
            requireCommonManifest(Files.readAllBytes(manifest), targets, allowNativeStdlib = logical == nativeStdlibPath)
            listOf(directory)
        } else {
            require(Files.isRegularFile(path) && (logical.endsWith(".jar") || logical.endsWith(".klib"))) { "Metadata input must be a root metadata JAR, KLIB or directory" }
            require(Files.size(path) in 1..METADATA_LIMIT.toLong()) { "Metadata archive exceeds 64 MiB" }
            val bytes = Files.readAllBytes(path)
            validateZip(bytes)
            ZipFile(path.toFile()).use { zip ->
                fun read(name: String, limit: Int = METADATA_LIMIT): ByteArray {
                    val entry = zip.getEntry(name) ?: error("Missing metadata archive entry: $name")
                    require(!entry.isDirectory && entry.size in 0..limit.toLong()) { "Invalid/oversized metadata entry: $name" }
                    return zip.getInputStream(entry).use { input -> input.readNBytes(limit + 1).also {
                        require(it.size <= limit && it.size.toLong() == entry.size) { "Metadata entry size differs: $name" }
                    } }
                }
                if (logical.endsWith(".klib")) {
                    requireCommonManifest(read("default/manifest", 1024 * 1024), targets)
                    listOf(path)
                } else {
                    val selected = commonSourceSets(parseJson(read(PSM_PATH, 1024 * 1024).decodeToString(throwOnInvalidSequence = true)), targets, minimumTargets)
                    selected.map { source ->
                        val destination = scratch.resolve(index.toString()).resolve(source)
                        Files.createDirectories(destination)
                        val prefix = "$source/"
                        val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }.toList()
                        require(entries.isNotEmpty()) { "Selected metadata source set has no KLIB: $source" }
                        requireCommonManifest(read("${source}/default/manifest", 1024 * 1024), targets)
                        for (entry in entries) {
                            val relative = LogicalPath(entry.name.removePrefix(prefix))
                            val output = destination.resolve(relative.value)
                            Files.createDirectories(output.parent)
                            Files.write(output, read(entry.name), CREATE_NEW, WRITE)
                        }
                        destination
                    }
                }
            }
        }
    }
}

private fun requireCommonManifest(bytes: ByteArray, targets: Set<String>, allowNativeStdlib: Boolean = false) {
    require(bytes.size <= 1024 * 1024) { "Metadata manifest exceeds 1 MiB" }
    val properties = Properties().apply { bytes.inputStream().use(::load) }
    require(properties.getProperty("unique_name")?.isNotBlank() == true && properties.getProperty("metadata_version") != null) { "Invalid common KLIB manifest" }
    if (allowNativeStdlib && properties.getProperty("builtins_platform") == "NATIVE" && properties.getProperty("unique_name") == "stdlib" &&
        targets.isNotEmpty() && targets.all { NativeTarget.find(it) != null }) {
        // Shared Native metadata needs genuine Native builtins. The graph supplies a verified,
        // digest-bound stdlib capture; no other platform KLIB is admitted as shared metadata.
        val supported = properties.getProperty("native_targets")?.split(Regex("\\s+"))?.toSet().orEmpty()
        require(targets.all { NativeTarget.require(it).compilerName in supported }) { "Native stdlib does not support the complete metadata target set" }
    } else require(properties.getProperty("builtins_platform") in setOf(null, "COMMON") && properties.getProperty("native_targets") == null) {
        "Platform KLIB is not common metadata"
    }
}

/** Names are the pinned KGP API-variant names, not an inferred arbitrary target prefix. */
internal fun commonSourceSets(value: JsonValue, targets: Set<String> = setOf("jvm", "macosArm64"), minimumTargets: Int = 2): List<String> {
    val root = value.objectValue().getValue("projectStructure").objectValue()
    require(root.getValue("formatVersion").stringValue() in setOf("0.3.1", "0.3.2", "0.3.3")) { "Unsupported project structure metadata" }
    val variants = root.getValue("variants").arrayValue().map(JsonValue::objectValue)
    require(variants.size in 1..256 && variants.map { it.getValue("name").stringValue() }.distinct().size == variants.size)
    fun variants(names: Set<String>): List<Set<String>> = variants.filter { it.getValue("name").stringValue() in names }.map {
        it.getValue("sourceSet").arrayValue().map(JsonValue::stringValue).also { names -> require(names.distinct().size == names.size) }.toSet()
    }
    require(targets.size >= minimumTargets && targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null })
    val selectedByTarget = targets.sorted().flatMap { target ->
        variants(setOf("${target}ApiElements", "${target}ApiElements-published")).ifEmpty {
            if (NativeTarget.find(target) != null) variants(setOf("nativeApiElements", "nativeApiElements-published")) else emptyList()
        }.also { require(it.isNotEmpty()) { "Metadata has no API variant for $target" } }
    }
    val selected = selectedByTarget.reduce { a, b -> a intersect b }
    require(selected.isNotEmpty()) { "Metadata has no shared source set" }
    val sourceSets = root.getValue("sourceSets").arrayValue().map(JsonValue::objectValue)
    require(sourceSets.size in 1..256 && sourceSets.map { it.getValue("name").stringValue() }.distinct().size == sourceSets.size)
    val byName = sourceSets.associateBy { it.getValue("name").stringValue() }
    for (name in selected) {
        require(name.length <= 128 && name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid metadata source set" }
        val source = byName[name] ?: error("Missing selected metadata source set: $name")
        require(source.getValue("binaryLayout").stringValue() == "klib") { "Only KLIB metadata is supported" }
        require(source.getValue("dependsOn").arrayValue().map(JsonValue::stringValue).all { it in selected }) { "Metadata source refinement is outside the shared intersection" }
    }
    return selected.sorted()
}

/** ZIP central framing/type checks include unselected payloads; ZIP64/encryption are unsupported. */
private fun validateZip(bytes: ByteArray) {
    fun u16(offset: Int): Int { require(offset >= 0 && offset + 2 <= bytes.size); return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) }
    fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    require(bytes.size >= 22)
    val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)).firstOrNull {
        u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
    } ?: error("Missing ZIP end record")
    require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "Multipart ZIP is unsupported" }
    val count = u16(end + 10)
    require(count in 1..10000 && u32(end + 16) + u32(end + 12) == end.toLong()) { "Invalid/bounded metadata ZIP directory" }
    var cursor = u32(end + 16).toInt()
    var total = 0L
    val paths = mutableSetOf<String>()
    val spellings = mutableMapOf<String, String>()
    val files = mutableSetOf<String>()
    repeat(count) {
        require(cursor >= 0 && cursor + 46 <= end && u32(cursor) == 0x02014b50L)
        val flags = u16(cursor + 8)
        require(flags and 1 == 0 && u16(cursor + 10) in setOf(0, 8)) { "Encrypted/unsupported ZIP entry" }
        val nameSize = u16(cursor + 28)
        val next = cursor + 46 + nameSize + u16(cursor + 30) + u16(cursor + 32)
        require(next <= end && nameSize in 1..4096 && u16(cursor + 34) == 0)
        val name = bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize).decodeToString(throwOnInvalidSequence = true)
        val path = LogicalPath(name.removeSuffix("/")).value
        require(paths.add(path) && path.split('/').size <= 64) { "Duplicate or deep metadata archive path" }
        val mode = ((u32(cursor + 38) shr 16).toInt() and 0xf000)
        require(mode == 0 || mode == if (name.endsWith('/')) 0x4000 else 0x8000) { "Metadata ZIP symlink/special entry" }
        var prefix = ""
        for (part in path.split('/')) {
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            val previous = spellings.put(prefix.lowercase(), prefix)
            require(previous == null || previous == prefix) { "Metadata archive case alias" }
        }
        if (!name.endsWith('/')) files += path
        total += u32(cursor + 24)
        require(total <= METADATA_LIMIT) { "Expanded metadata archive exceeds 64 MiB" }
        cursor = next
    }
    require(cursor == end)
    for (path in paths) require(path.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.none { it in files }) { "Metadata ZIP file/directory collision" }
}
