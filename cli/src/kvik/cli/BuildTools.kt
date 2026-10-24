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
    val declaration: ToolchainDeclaration
    val roots: Map<ToolComponent, String>
    val data: String
    val artifacts: String
    init {
        fields.requireVersion()
        lock = fields.getValue("toolLock").canonical()
        declaration = parseToolchainDeclaration(lock)
        val components = declaration.components.keys
        val paths = fields.getValue("roots").fields(components.map { it.lockName }.toSet())
        roots = components.associateWith { component -> paths.getValue(component.lockName).stringValue().also(NativeFiles::validateAbsolute) }
        data = fields.getValue("konanData").stringValue().also(NativeFiles::validateAbsolute)
        artifacts = fields.getValue("compilerArtifacts").stringValue().also(NativeFiles::validateAbsolute)
    }

    /** Validated desired identities authorize matching cached artifacts, not installed tool paths. */
    fun desiredIdentities(targets: Set<String>): Map<String, Map<String, Digest>> {
        require(targets.isNotEmpty() && targets.all { it == "jvm" || NativeTarget.find(it) != null }) { "Unsupported tool targets" }
        fun identity(component: ToolComponent) = declaration.components.getValue(component)
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

    /** Verify only the JDK used by a restored JVM application; compiler/SDK paths are irrelevant. */
    fun verifiedJavaExecutable(expected: Digest, checkpoint: () -> Unit): String {
        require(declaration.components.getValue(ToolComponent.JDK) == expected) { "Desired JDK identity changed" }
        val jdk = InstalledToolchainVerifier().verifyJdk(lock, roots.getValue(ToolComponent.JDK), checkpoint)
        require(jdk.digest == expected) { "Installed JDK differs from the prepared application" }
        val java = "${jdk.root}/bin/java"
        NativeFiles.ensureDirectory("${jdk.root}/bin", create = false)
        val info = NativeFiles.info(java) ?: error("Missing verified JDK java executable")
        require(NativeFiles.regular(info) && info.mode and 73 != 0) { "Verified JDK java must be a regular executable" }
        checkpoint()
        return java
    }

    fun jvmProvider() = InstalledJvmToolchainProvider(lock, roots, "${runtimeDirectory()}/jvm",
        Digest(runtimeJvmWorkerHash, runtimeJvmWorkerSize), artifacts)
    fun nativeProvider(target: NativeTarget = if (ToolComponent.SYSTEM in roots) NativeTarget.LINUX_X64 else NativeTarget.MACOS_ARM64) =
        InstalledNativeToolchainProvider(lock, roots, data, target)
}
