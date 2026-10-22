package kvik.cli

import kvik.cache.NativeFiles
import kvik.compilertools.*
import kvik.compiler.native.nativeDependenciesIdentity
import kvik.core.*
import kvik.toolchains.*

/** Explicit trusted installations; paths never participate in the portable action identity. */
internal class BuildTools(path: String) {
    val document = parseJson(NativeFiles.read(path, 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
    private val fields = document.fields(setOf("schemaVersion", "toolLock", "roots", "konanData", "compilerArtifacts"))
    val lock: String
    val roots: Map<ToolComponent, String>
    val data: String
    val artifacts: String
    init {
        fields.requireVersion()
        lock = fields.getValue("toolLock").canonical()
        val paths = fields.getValue("roots").fields(ToolComponent.entries.map { it.lockName }.toSet())
        roots = ToolComponent.entries.associateWith { component -> paths.getValue(component.lockName).stringValue().also(NativeFiles::validateAbsolute) }
        data = fields.getValue("konanData").stringValue().also(NativeFiles::validateAbsolute)
        artifacts = fields.getValue("compilerArtifacts").stringValue().also(NativeFiles::validateAbsolute)
    }

    fun verify(targets: Set<String>): Map<String, Map<String, Digest>> {
        // Cache hits still require a verified current tool identity, not just user-supplied hashes.
        val expected = parseJson(lock).objectValue().getValue("components").objectValue()
        fun identity(component: ToolComponent) = Digest.fromJson(expected.getValue(component.lockName))
        val result = mutableMapOf<String, Map<String, Digest>>()
        if ("jvm" in targets) {
            val worker = verifiedRuntimeJvmWorker()
            result["jvm"] = mapOf("jdk" to identity(ToolComponent.JDK), "jvm-compiler" to worker.compiler,
                "jvm-worker" to worker.identity, "junit" to worker.junitIdentity)
            jvmProvider().verified(result.getValue("jvm"))
        }
        if ("macosArm64" in targets) {
            result["macosArm64"] = mapOf("native" to identity(ToolComponent.KOTLIN_NATIVE),
                "jdk" to identity(ToolComponent.JDK), "sdk" to identity(ToolComponent.SDK),
                "native-dependencies" to nativeDependenciesIdentity(identity(ToolComponent.LLVM),
                    identity(ToolComponent.LIBFFI), identity(ToolComponent.XCODE_TOOLCHAIN)))
            nativeProvider().verified(result.getValue("macosArm64"))
        }
        return result
    }

    fun jvmProvider() = InstalledJvmToolchainProvider(lock, roots, "${runtimeDirectory()}/jvm",
        Digest(runtimeJvmWorkerHash, runtimeJvmWorkerSize), artifacts)
    fun nativeProvider() = InstalledNativeToolchainProvider(lock, roots, data)
}
