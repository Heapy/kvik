@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
package kvik.jvm

import kvik.core.*
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermission
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/** One owned tool JVM. No consumer code is loaded or executed. */
object AbiWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "AbiWorker <workspace> <request> <result> <compiler-artifacts> <manifest>" }
        val workspace = Workspace(Path.of(args[0]))
        var requestId = "invalid-request"
        val result = try {
            val request = AbiWorkerRequest.fromJson(workspace.readJson(args[1]))
            requestId = request.requestId
            val manifest = Path.of(args[4])
            require(Files.isRegularFile(manifest, NOFOLLOW_LINKS) && Files.size(manifest) <= 4 * 1024 * 1024) { "Invalid compiler manifest" }
            val pinned = distribution(Path.of(args[3]), parseJson(Files.readString(manifest)), request.compiler)
            val worker = Path.of(AbiWorker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
            val bootstrap = listOf(worker,
                Path.of(org.jetbrains.kotlin.buildtools.api.KotlinToolchains::class.java.protectionDomain.codeSource.location.toURI()).toRealPath(),
                Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toRealPath())
            require(System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toRealPath() } == bootstrap) {
                "Unexpected ABI worker bootstrap classpath"
            }
            val scratch = Files.createTempDirectory(workspace.root, ".abi-")
            try {
                val artifact = verifiedCopy(workspace, request, scratch)
                val api = URLClassLoader((pinned.classpath + listOf(worker)).map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                    val reader = loader.loadClass("kvik.jvm.AbiReader")
                    require(reader.classLoader === loader) { "ABI reader escaped its isolated distribution" }
                    reader.getMethod("read", String::class.java, String::class.java, String::class.java)
                        .invoke(null, artifact.toString(), request.extraction.target, pinned.version) as String
                }
                val snapshot = AbiSnapshot(request.extraction.module, request.extraction.target, Sha256.digest(request.inputs.toJson()), api, compilerVersion = pinned.version)
                val output = workspace.path("output", false)
                require(!Files.exists(output, NOFOLLOW_LINKS)) { "ABI output already exists" }
                val staged = Files.createDirectory(scratch.resolve("output"))
                val snapshotBytes = snapshot.toJson().canonical().encodeToByteArray()
                require(snapshotBytes.size <= 64 * 1024 * 1024) { "ABI snapshot JSON exceeds 64 MiB" }
                Files.write(staged.resolve("abi.json"), snapshotBytes)
                Files.write(staged.resolve("api.txt"), api.encodeToByteArray())
                Files.move(staged, output, ATOMIC_MOVE)
            } finally { workspace.removeTree(scratch) }
            WorkerResult(requestId, WorkerStatus.SUCCESS, 0, emptyList())
        } catch (failure: Exception) {
            val cause = if (failure is InvocationTargetException) failure.targetException else failure
            val message = (cause.message ?: cause.javaClass.simpleName).take(4096).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            WorkerResult(requestId, WorkerStatus.INFRASTRUCTURE_ERROR, 1,
                listOf(Diagnostic((cause as? AdapterFailure)?.code ?: "KVIK_ABI_EXTRACTION", DiagnosticCategory.INFRASTRUCTURE, message)))
        }
        workspace.publish(args[2], result.toJson())
        exitProcess(result.exitCode)
    }

    private fun verifiedCopy(workspace: Workspace, request: AbiWorkerRequest, scratch: Path): Path {
        val input = workspace.path("input")
        require(Files.isDirectory(input, NOFOLLOW_LINKS)) { "ABI inputs must be a directory" }
        val declared = request.inputs.files.associateBy { it.path.value }
        val directories = declared.keys.flatMap { name -> name.indices.filter { name[it] == '/' }.map { name.substring(0, it) } }.toSet()
        val seen = mutableSetOf<String>()
        Files.walk(input).use { paths -> paths.forEach { path ->
            if (path != input) {
                val name = input.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                require(!Files.isSymbolicLink(path)) { "Symlink ABI input: $name" }
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) require(name in directories) { "Undeclared ABI directory: $name" }
                else {
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && name in declared && seen.add(name)) { "Undeclared or special ABI input: $name" }
                }
            }
        } }
        require(seen == declared.keys) { "ABI inputs differ from their manifest" }
        var total = 0L
        fun bytes(entry: FileEntry): ByteArray {
            require(entry.digest.sizeBytes <= 64L * 1024 * 1024) { "ABI input exceeds 64 MiB" }
            total += entry.digest.sizeBytes
            require(total <= 256L * 1024 * 1024) { "ABI inputs exceed 256 MiB" }
            val path = workspace.path("input/${entry.path.value}")
            val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
            require(permissions.none { it in setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE) }) { "ABI input executable mode differs" }
            val data = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(64 * 1024 * 1024 + 1) }
            require(Sha256.digest(data) == entry.digest) { "ABI input digest differs: ${entry.path.value}" }
            return data
        }
        val artifact = scratch.resolve(if (request.extraction.target == "jvm") "classes.jar" else "library.klib")
        if (request.extraction.target == "jvm") {
            JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
                for (entry in request.inputs.files.sortedBy { it.path.value }) {
                    jar.putNextEntry(JarEntry(entry.path.value.removePrefix(request.extraction.artifact.value + "/")).also { it.time = 0 })
                    jar.write(bytes(entry)); jar.closeEntry()
                }
            }
        } else {
            Files.write(artifact, bytes(request.inputs.files.single()))
            // Bound decompression before the compiler reader opens the private KLIB.
            var expanded = 0L
            val names = mutableSetOf<String>()
            ZipFile(artifact.toFile()).use { zip ->
                val manifestEntry = requireNotNull(zip.getEntry("default/manifest")) { "Missing KLIB manifest" }
                val manifestBytes = zip.getInputStream(manifestEntry).use { it.readNBytes(1024 * 1024 + 1) }
                require(manifestBytes.size <= 1024 * 1024) { "KLIB manifest exceeds 1 MiB" }
                val properties = java.util.Properties().apply { manifestBytes.inputStream().use(::load) }
                val target = request.extraction.target
                val native = NativeTarget.find(target)
                val matches = if (native != null) {
                    properties.getProperty("builtins_platform") == "NATIVE" &&
                        properties.getProperty("native_targets") == native.compilerName && properties.getProperty("wasm_targets") == null
                } else when (KotlinJsTarget.require(target)) {
                    KotlinJsTarget.JS -> properties.getProperty("builtins_platform") == "JS" &&
                        properties.getProperty("native_targets") == null && properties.getProperty("wasm_targets") == null
                    KotlinJsTarget.WASM_JS -> properties.getProperty("builtins_platform") == "WASM" &&
                        properties.getProperty("wasm_targets") == "wasm-js" && properties.getProperty("native_targets") == null
                }
                require(matches) { "KLIB platform/target differs from requested ABI target" }
                zip.entries().asSequence().forEach { entry ->
                require(names.size < 100_000 && names.add(entry.name)) { "Invalid KLIB entry count or duplicate" }
                LogicalPath(entry.name.removeSuffix("/"))
                zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val size = stream.read(buffer); if (size < 0) break
                        expanded += size
                        require(expanded <= 256L * 1024 * 1024) { "KLIB expanded data exceeds 256 MiB" }
                    }
                }
            } }
        }
        return artifact
    }
}
