package kvik.jvm

import kvik.core.*
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import org.jacoco.cli.internal.core.analysis.Analyzer
import org.jacoco.cli.internal.core.analysis.CoverageBuilder
import org.jacoco.cli.internal.core.analysis.ISourceFileCoverage
import org.jacoco.cli.internal.core.tools.ExecFileLoader
import org.jacoco.cli.internal.report.ISourceFileLocator
import org.jacoco.cli.internal.report.xml.XMLFormatter
import kotlin.system.exitProcess

/** Analyze retained data only. No class loading, tests, source discovery, XML input, or network access. */
object CoverageWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "CoverageWorker <workspace> <request-relative> <result-relative>" }
        val workspace = Workspace(Path.of(args[0]))
        var requestId = "invalid-request"
        var exit = 0
        val result = try {
            val request = workspace.readJson(args[1]).fields(setOf("schemaVersion", "requestId", "specification", "executionData", "executionComplete", "files"))
            request.requireVersion()
            requestId = request.getValue("requestId").stringValue().also { require(it.length in 1..1024) }
            val specification = JvmCoverageSpec.fromJson(request.getValue("specification"))
            verifyClasspath(specification.reporter)
            analyze(workspace, requestId, specification, request.getValue("executionData").arrayValue().map(FileEntry::fromJson),
                request.getValue("executionComplete").booleanValue(), request.getValue("files").arrayValue().map(FileEntry::fromJson))
        } catch (failure: Exception) {
            exit = 1
            obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(), "status" to "incomplete".json(),
                "sources" to emptyList<JsonValue>().json(), "xml" to emptyList<JsonValue>().json(),
                "diagnostics" to listOf(diagnosticText(failure.message ?: failure.javaClass.simpleName).json()).json())
        }
        workspace.publish(args[2], result)
        exitProcess(exit)
    }

    private fun verifyClasspath(reporter: Digest) {
        fun location(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
        val cli = location(Analyzer::class.java)
        require(fileDigest(cli) == reporter) { "JaCoCo reporter identity differs" }
        require(System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toRealPath() } ==
            listOf(location(CoverageWorker::class.java), cli, location(kotlin.Unit::class.java))) { "Coverage classpath must be exactly worker, pinned JaCoCo CLI, stdlib" }
    }

    internal fun analyze(workspace: Workspace, requestId: String, specification: JvmCoverageSpec, executionData: List<FileEntry>,
        executionComplete: Boolean, files: List<FileEntry>): JsonValue {
        require(specification.excludes.isEmpty()) { "Coverage exclusion normalization is not supported" }
        require(executionData.size in 1..1000 && executionData.map { it.path }.distinct().size == executionData.size)
        require(files.size <= 100_000)
        TreeManifest(files)
        val roots = specification.classDirectories
        require(roots.none { a -> roots.any { b -> a != b && b.path.value.startsWith(a.path.value + "/") } }) { "Overlapping class roots" }
        val allPaths = files.map { it.path }.toSet()
        require(executionData.none { it.path in allPaths }) { "Execution data overlaps class inputs" }
        require(files.all { file -> roots.count { file.path.value.startsWith(it.path.value + "/") } == 1 }) { "Class input outside its module root" }
        var totalBytes = 0L
        fun bytes(path: LogicalPath, digest: Digest, limit: Long): ByteArray {
            require(digest.sizeBytes <= limit) { "Coverage input exceeds byte bound: ${path.value}" }
            totalBytes += digest.sizeBytes
            require(totalBytes <= 1024L * 1024 * 1024) { "Coverage inputs exceed aggregate byte bound" }
            val physical = workspace.path(path.value)
            require(Files.isRegularFile(physical, NOFOLLOW_LINKS) && Files.size(physical) == digest.sizeBytes) { "Coverage input is missing, special, or differs: ${path.value}" }
            val data = Files.newInputStream(physical, NOFOLLOW_LINKS).use { input -> input.readNBytes(limit.toInt() + 1) }
            require(data.size.toLong() <= limit && Sha256.digest(data) == digest) { "Coverage input digest differs: ${path.value}" }
            return data
        }
        val loader = ExecFileLoader()
        for (entry in executionData) bytes(entry.path, entry.digest, 64L * 1024 * 1024).inputStream().use(loader::load)
        val measured = executionComplete && loader.sessionInfoStore.infos.isNotEmpty()
        val diagnostics = mutableListOf<String>()
        if (!measured) diagnostics += "Execution did not complete or JaCoCo session information is missing; normalized measurements are unknown"
        diagnostics += "Normalized metrics contain LINE coverage only; original JaCoCo XML retains branch, instruction, method and class metrics"
        var totalLines = 0L
        val sourceLines = specification.sources.associateWith { source ->
            lineCount(bytes(source.inputPath, source.digest, 16L * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)).also { count ->
                totalLines += count
                require(totalLines <= 250_000) { "Coverage sources exceed aggregate line bound" }
            }
        }
        if (sourceLines.isEmpty()) diagnostics += "No declared source inputs are available for normalized coverage"
        val normalized = mutableListOf<SourceCoverage>()
        val xml = mutableListOf<JsonValue>()
        for (root in roots) {
            val physicalRoot = workspace.path(root.path.value)
            require(Files.isDirectory(physicalRoot, NOFOLLOW_LINKS)) { "Production class root is not a directory" }
            val declared = files.filter { it.path.value.startsWith(root.path.value + "/") }.associateBy { it.path.value.removePrefix(root.path.value + "/") }
            val present = mutableSetOf<String>()
            Files.walk(physicalRoot).use { paths ->
                paths.forEach { path ->
                    require(!Files.isSymbolicLink(path) && (Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS))) { "Unsafe production class tree" }
                    if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        require(present.size < 100_000) { "Production class tree exceeds file bound" }
                        present += physicalRoot.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                    }
                }
            }
            require(present == declared.keys) { "Production class tree differs from its input manifest" }
            val builder = CoverageBuilder()
            val classNames = mutableSetOf<String>()
            val analyzer = Analyzer(loader.executionDataStore) { coverage ->
                require(classNames.add(coverage.name)) { "Duplicate production class: ${coverage.name}" }
                builder.visitCoverage(coverage)
            }
            for ((relative, entry) in declared.toSortedMap()) {
                val data = bytes(entry.path, entry.digest, 32L * 1024 * 1024)
                if (relative.endsWith(".class")) analyzer.analyzeClass(data, entry.path.value)
            }
            val moduleSources = specification.sources.filter { it.module == root.module }
            val sourceGroups = moduleSources.groupBy { it.path.value.substringAfterLast('/') }
            val measuredGroups = builder.sourceFiles.groupBy { it.name }
            val missingSourceInfo = builder.classes.any { it.sourceFileName == null }
            val allClasses = builder.classes.toList()
            for (source in moduleSources) {
                val name = source.path.value.substringAfterLast('/')
                val count = sourceLines.getValue(source)
                val sourceDiagnostics = mutableListOf<String>()
                val candidates = measuredGroups[name].orEmpty()
                if (!measured) sourceDiagnostics += "Incomplete execution data"
                if (sourceGroups.getValue(name).size != 1 || candidates.size > 1) sourceDiagnostics += "Ambiguous source basename within module: $name"
                if (candidates.isEmpty()) sourceDiagnostics += "No matching source debug information: $name"
                if (missingSourceInfo) sourceDiagnostics += "A production class lacks source-file debug information"
                val candidate = candidates.singleOrNull()
                if (candidate != null) {
                    val classes = allClasses.filter { it.sourceFileName == name && it.packageName == candidate.packageName }
                    if (classes.any { it.isNoMatch }) sourceDiagnostics += "Execution data does not match original class bytes"
                    if (classes.any { it.instructionCounter.totalCount > 0 && it.lineCounter.totalCount == 0 })
                        sourceDiagnostics += "A production class lacks line-number debug information"
                    require(candidate.firstLine == -1 || candidate.firstLine in 1..count) { "Coverage line outside original source: ${source.path.value}" }
                    require(candidate.lastLine == -1 || candidate.lastLine in 1..count) { "Coverage line outside original source: ${source.path.value}" }
                }
                val complete = sourceDiagnostics.isEmpty()
                normalized += SourceCoverage(source, count, (1..count).map { number ->
                    CoverageLine(number, if (!complete) LineCoverageState.UNKNOWN else lineState(checkNotNull(candidate), number))
                }, complete, sourceDiagnostics)
            }
            val unmapped = measuredGroups.keys - sourceGroups.keys
            if (unmapped.isNotEmpty()) diagnostics += "${root.module}: ${unmapped.size} compiled source names have no declared source input"
            val destination = "coverage/${root.module}/jacoco.xml"
            require(files.none { it.path.value == destination || it.path.value.startsWith("coverage/") } &&
                specification.sources.none { it.inputPath.value == destination } && executionData.none { it.path.value == destination }) { "Report output overlaps input" }
            val output = workspace.path(destination, false)
            Files.createDirectories(output.parent)
            // Recheck after directory creation; the caller owns and keeps this workspace stable.
            workspace.path(destination, false)
            require(!Files.exists(output, NOFOLLOW_LINKS)) { "Coverage XML already exists" }
            val temporary = Files.createTempFile(output.parent, ".jacoco-", ".xml")
            try {
                Files.newOutputStream(temporary).use { stream ->
                    val visitor = XMLFormatter().createVisitor(LimitedOutput(stream, 64L * 1024 * 1024))
                    visitor.visitInfo(loader.sessionInfoStore.infos, loader.executionDataStore.contents)
                    visitor.visitBundle(builder.getBundle(root.module), object : ISourceFileLocator {
                        override fun getTabWidth(): Int = 4
                        override fun getSourceFile(packageName: String, fileName: String): java.io.Reader? = null
                    })
                    visitor.visitEnd()
                }
                Files.move(temporary, output, ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temporary) }
            xml += obj("module" to root.module.json(), "path" to destination.json(), "digest" to fileDigest(output).toJson())
        }
        val complete = measured && normalized.all { it.complete } && diagnostics.size == 1
        return obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(), "status" to (if (complete) "complete" else "incomplete").json(),
            "sources" to normalized.map { it.toJson() }.json(), "xml" to xml.json(), "diagnostics" to diagnostics.map { it.json() }.json())
    }

    internal fun lineCount(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            count++
            while (index < text.length && text[index] != '\n' && text[index] != '\r') index++
            if (index < text.length && text[index++] == '\r' && index < text.length && text[index] == '\n') index++
        }
        require(count <= 1_000_000) { "Source exceeds coverage line bound" }
        return count
    }

    private fun lineState(source: ISourceFileCoverage, number: Int): LineCoverageState {
        val counter = source.getLine(number).instructionCounter
        require(counter.coveredCount >= 0 && counter.missedCount >= 0) { "Invalid line counters" }
        return when {
            counter.coveredCount > 0 -> LineCoverageState.COVERED
            counter.missedCount > 0 -> LineCoverageState.UNCOVERED
            else -> LineCoverageState.NON_EXECUTABLE
        }
    }

    private fun diagnosticText(value: String): String = buildString {
        var index = 0
        while (index < value.length && length < 8192) {
            val c = value[index++]
            if (c.isHighSurrogate() && index < value.length && value[index].isLowSurrogate()) {
                if (length + 2 > 8192) break
                append(c).append(value[index++])
            } else append(if (c.isSurrogate()) '\uFFFD' else c)
        }
    }

    private class LimitedOutput(private val destination: OutputStream, private val limit: Long) : OutputStream() {
        private var size = 0L
        override fun write(value: Int) { require(++size <= limit) { "Coverage XML exceeds byte bound" }; destination.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            size += length; require(size <= limit) { "Coverage XML exceeds byte bound" }; destination.write(bytes, offset, length)
        }
        override fun flush() = destination.flush()
        override fun close() = destination.close()
    }
}
