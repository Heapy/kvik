@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package kvik.jvm

import kvik.core.*
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import kotlin.system.exitProcess

/** Foreground-owned sequential worker. Its guardian owns lifetime, deadlines and cancellation. */
object CompilerSessionWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        try { serve(args) }
        catch (failure: Exception) {
            failure.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun realDirectory(path: Path): Path {
        require(path.isAbsolute && path.normalize() == path && Files.isDirectory(path, NOFOLLOW_LINKS)) { "Expected normalized directory" }
        require(path.toRealPath() == path) { "Directory aliases are forbidden" }
        return path
    }

    private fun readBounded(path: Path, limit: Int): ByteArray {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Expected regular mailbox file" }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            input.readNBytes(limit + 1).also { require(it.size in 1..limit) { "Mailbox file exceeds its bound" } }
        }
    }

    private fun publish(path: Path, bytes: ByteArray) {
        require(!Files.exists(path, NOFOLLOW_LINKS)) { "Acknowledgement already exists" }
        val temporary = Files.createTempFile(path.parent, ".kvik-ack-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun serve(args: Array<String>) {
        require(args.size == 4) { "CompilerSessionWorker <session> <action-root> <compiler-artifacts> <manifest>" }
        val session = realDirectory(Path.of(args[0]))
        val requests = realDirectory(session.resolve("requests"))
        val results = realDirectory(session.resolve("results"))
        val actionRoot = realDirectory(Path.of(args[1]))
        val artifacts = realDirectory(Path.of(args[2]))
        val manifestPath = Path.of(args[3])
        val directories = mutableSetOf<Path>()
        val requestIds = mutableSetOf<String>()
        var toolchains: KotlinToolchains? = null
        var compilerIdentity: Digest? = null
        for (sequence in 1L..1_000_000L) {
            val path = requests.resolve("$sequence.json")
            while (!Files.exists(path, NOFOLLOW_LINKS)) Thread.sleep(20)
            val envelopeBytes = readBounded(path, JVM_COMPILER_SESSION_REQUEST_BYTES)
            val envelope = CompilerSessionRequest.parse(envelopeBytes)
            require(envelope.sequence == sequence) { "Out-of-order compiler request" }
            val directory = realDirectory(Path.of(envelope.directory))
            require(directory != actionRoot && directory.startsWith(actionRoot)) { "Action outside the allowed root" }
            require(!directory.startsWith(session) && !session.startsWith(directory)) { "Action overlaps the mailbox" }
            require(directories.add(directory)) { "Repeated action directory" }
            val workspace = Workspace(directory)
            val requestBytes = readBounded(workspace.path("request.json"), JVM_COMPILER_SESSION_REQUEST_BYTES)
            require(Sha256.digest(requestBytes) == envelope.request) { "Action request digest mismatch" }
            val request = WorkerRequest.fromJson(parseJson(requestBytes.decodeToString(throwOnInvalidSequence = true)))
            require(requestIds.add(request.requestId)) { "Repeated action request ID" }
            require(request.operation == "jvm-compile" && request.pluginClasspath.isEmpty() && request.pluginApplications.isEmpty()) {
                "Reusable workers accept only plugin-free JVM compilations"
            }
            val home = realDirectory(workspace.path("home"))
            val temporary = realDirectory(workspace.path("tmp"))
            for (name in listOf("result.json", "worker.stdout", "worker.stderr"))
                require(!Files.exists(workspace.path(name, false), NOFOLLOW_LINKS)) { "Action output already exists: $name" }
            val oldOut = System.out
            val oldErr = System.err
            // Restore the complete property set, including compiler implementation mutations.
            val oldProperties = System.getProperties().clone() as java.util.Properties
            val result: WorkerResult
            try {
                PrintStream(Files.newOutputStream(workspace.path("worker.stdout", false), CREATE_NEW, WRITE), true, Charsets.UTF_8).use { out ->
                    PrintStream(Files.newOutputStream(workspace.path("worker.stderr", false), CREATE_NEW, WRITE), true, Charsets.UTF_8).use { err ->
                        System.setOut(out)
                        System.setErr(err)
                        System.setProperty("user.home", home.toString())
                        System.setProperty("java.io.tmpdir", temporary.toString())
                        result = try {
                            val manifest = parseJson(readBounded(manifestPath, JVM_COMPILER_SESSION_REQUEST_BYTES).decodeToString(throwOnInvalidSequence = true))
                            val verified = distribution(artifacts, manifest, request.compiler)
                            require(compilerIdentity == null || compilerIdentity == request.compiler) { "Compiler changed during session" }
                            val implementation = toolchains ?: KotlinToolchains.loadImplementation(verified.classpath).also {
                                require(it.getCompilerVersion() == verified.version) { "Loaded compiler version mismatch" }
                                toolchains = it
                                compilerIdentity = request.compiler
                            }
                            compile(workspace, request, verified, implementation)
                        } catch (failure: Exception) {
                            failure.printStackTrace(err)
                            WorkerResult(request.requestId, WorkerStatus.INFRASTRUCTURE_ERROR, 1, listOf(Diagnostic(
                                (failure as? AdapterFailure)?.code ?: "KVIK_WORKER_INFRASTRUCTURE", DiagnosticCategory.INFRASTRUCTURE,
                                failure.message ?: failure::class.simpleName ?: "Worker failed")))
                        }
                        out.flush()
                        err.flush()
                        check(!out.checkError() && !err.checkError()) { "Action log write failed" }
                    }
                }
            } finally {
                System.setOut(oldOut)
                System.setErr(oldErr)
                System.setProperties(oldProperties)
            }
            workspace.publish("result.json", result.toJson())
            val ack = CompilerSessionResult(sequence, Sha256.digest(envelopeBytes), fileDigest(workspace.path("result.json")),
                result.exitCode, ProcessHandle.current().pid())
            publish(results.resolve("$sequence.json"), ack.bytes())
            if (result.status != WorkerStatus.SUCCESS && result.status != WorkerStatus.COMPILATION_ERROR) exitProcess(result.exitCode)
        }
    }
}
