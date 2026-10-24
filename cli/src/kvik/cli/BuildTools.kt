package kvik.cli

import kvik.cache.NativeFiles
import kvik.compilertools.*
import kvik.compiler.native.nativeDependenciesIdentity
import kvik.compiler.native.linuxNativeDependenciesIdentity
import kvik.compiler.native.NativeToolchainProvider
import kvik.compiler.native.NativeToolchainLayout
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.compiler.jvm.JvmToolchainLayout
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
        val result = declaredIdentities(targets)
        for ((target, identities) in result) verifyTarget(target, identities) {}
        return result
    }

    fun admission(targets: Set<String>) = BuildToolAdmission(declaredIdentities(targets), ::verifyTarget)

    private fun verifyTarget(target: String, identities: Map<String, Digest>, checkpoint: () -> Unit) {
        if (target == "jvm") jvmProvider().verified(identities, checkpoint)
        else nativeProvider(NativeTarget.require(target)).verified(identities, checkpoint)
    }

    /** Unverified installed identities are suitable only for provisional graph/cache planning. */
    private fun declaredIdentities(targets: Set<String>): Map<String, Map<String, Digest>> {
        require(targets.isNotEmpty() && targets.all { it == "jvm" || NativeTarget.find(it) != null }) { "Unsupported tool targets" }
        val expected = parseJson(lock).objectValue().getValue("components").objectValue()
        fun identity(component: ToolComponent) = Digest.fromJson(expected.getValue(component.lockName))
        val result = mutableMapOf<String, Map<String, Digest>>()
        if ("jvm" in targets) {
            val worker = verifiedRuntimeJvmWorker()
            result["jvm"] = mapOf("jdk" to identity(ToolComponent.JDK), "jvm-compiler" to worker.compiler,
                "jvm-worker" to worker.identity, "junit" to worker.junitIdentity)
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
        }
        return result
    }

    fun jvmProvider(admission: BuildToolAdmission? = null): JvmToolchainProvider {
        val provider = InstalledJvmToolchainProvider(lock, roots, "${runtimeDirectory()}/jvm",
            Digest(runtimeJvmWorkerHash, runtimeJvmWorkerSize), artifacts)
        if (admission == null) return provider
        return object : JvmToolchainProvider {
            override fun verified(identities: Map<String, Digest>): JvmToolchainLayout = verified(identities) {}
            override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout =
                admission.verified("jvm", identities, checkpoint) { provider.verified(identities, checkpoint) }
        }
    }
    fun nativeProvider(target: NativeTarget = if (ToolComponent.SYSTEM in roots) NativeTarget.LINUX_X64 else NativeTarget.MACOS_ARM64,
                       admission: BuildToolAdmission? = null): NativeToolchainProvider {
        val provider = InstalledNativeToolchainProvider(lock, roots, data, target)
        if (admission == null) return provider
        return object : NativeToolchainProvider {
            override fun verified(identities: Map<String, Digest>): NativeToolchainLayout = verified(identities) {}
            override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): NativeToolchainLayout =
                admission.verified(target.projectName, identities, checkpoint) { provider.verified(identities, checkpoint) }
        }
    }
}
