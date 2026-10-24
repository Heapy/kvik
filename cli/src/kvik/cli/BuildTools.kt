package kvik.cli

import kvik.cache.NativeFiles
import kvik.compilertools.*
import kvik.compiler.native.nativeDependenciesIdentity
import kvik.compiler.native.linuxNativeDependenciesIdentity
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
        val host = fields.getValue("toolLock").objectValue().getValue("host").objectValue()
        val components = toolComponentsFor(host.getValue("os").stringValue(), host.getValue("architecture").stringValue())
        val paths = fields.getValue("roots").fields(components.map { it.lockName }.toSet())
        roots = components.associateWith { component -> paths.getValue(component.lockName).stringValue().also(NativeFiles::validateAbsolute) }
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
        for (target in targets.mapNotNull(NativeTarget::find)) {
            val hostComponents = if (target == NativeTarget.MACOS_ARM64)
                setOf(ToolComponent.SDK, ToolComponent.XCODE_TOOLCHAIN)
            else setOf(ToolComponent.SYSTEM, ToolComponent.NATIVE_TOOLCHAIN)
            require(roots.keys.containsAll(hostComponents)) { "${target.projectName} compilation requires its matching Native host" }
            val hostIdentity = if (target == NativeTarget.MACOS_ARM64)
                "sdk" to identity(ToolComponent.SDK) else "system" to identity(ToolComponent.SYSTEM)
            val dependencies = if (target == NativeTarget.MACOS_ARM64)
                nativeDependenciesIdentity(identity(ToolComponent.LLVM), identity(ToolComponent.LIBFFI), identity(ToolComponent.XCODE_TOOLCHAIN))
            else linuxNativeDependenciesIdentity(identity(ToolComponent.LLVM), identity(ToolComponent.LIBFFI), identity(ToolComponent.NATIVE_TOOLCHAIN))
            result[target.projectName] = mapOf("native" to identity(ToolComponent.KOTLIN_NATIVE),
                "jdk" to identity(ToolComponent.JDK), hostIdentity, "native-dependencies" to dependencies)
            nativeProvider(target).verified(result.getValue(target.projectName))
        }
        return result
    }

    fun jvmProvider() = InstalledJvmToolchainProvider(lock, roots, "${runtimeDirectory()}/jvm",
        Digest(runtimeJvmWorkerHash, runtimeJvmWorkerSize), artifacts)
    fun nativeProvider(target: NativeTarget = if (ToolComponent.SYSTEM in roots) NativeTarget.LINUX_X64 else NativeTarget.MACOS_ARM64) =
        InstalledNativeToolchainProvider(lock, roots, data, target)
}
