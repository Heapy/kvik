package kvik.cli

import kvik.cache.NativeFiles
import kvik.compilertools.*
import kvik.compiler.js.NodeToolchainProvider
import kvik.compiler.native.nativeDependenciesIdentity
import kvik.compiler.native.linuxNativeDependenciesIdentity
import kvik.core.*
import kvik.toolchains.*

/** Explicit trusted installations; paths never participate in the portable action identity. */
internal class BuildTools(path: String) {
    val document = parseJson(NativeFiles.read(path, 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
    private val fields = document.fields(setOf("schemaVersion", "toolLock", "roots", "konanData", "compilerArtifacts"), setOf("node"))
    val lock: String
    val declaration: ToolchainDeclaration
    val roots: Map<ToolComponent, String>
    val data: String
    val artifacts: String
    private val node: Pair<String, Digest>?
    init {
        fields.requireVersion()
        lock = fields.getValue("toolLock").canonical()
        declaration = parseToolchainDeclaration(lock)
        val components = declaration.components.keys
        val paths = fields.getValue("roots").fields(components.map { it.lockName }.toSet())
        roots = components.associateWith { component -> paths.getValue(component.lockName).stringValue().also(NativeFiles::validateAbsolute) }
        data = fields.getValue("konanData").stringValue().also(NativeFiles::validateAbsolute)
        artifacts = fields.getValue("compilerArtifacts").stringValue().also(NativeFiles::validateAbsolute)
        node = fields["node"]?.fields(setOf("root", "identity"))?.let {
            it.getValue("root").stringValue().also(NativeFiles::validateAbsolute) to Digest.fromJson(it.getValue("identity"))
        }
    }

    /** Validated desired identities authorize matching cached artifacts, not installed tool paths. */
    fun desiredIdentities(targets: Set<String>, nodeTargets: Set<String> = emptySet()): Map<String, Map<String, Digest>> {
        require(targets.isNotEmpty() && targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null }) { "Unsupported tool targets" }
        require(nodeTargets.all { it in targets && KotlinJsTarget.find(it) != null }) { "Invalid Node tool targets" }
        fun identity(component: ToolComponent) = declaration.components.getValue(component)
        val result = mutableMapOf<String, Map<String, Digest>>()
        if ("jvm" in targets || targets.any { KotlinJsTarget.find(it) != null }) {
            val worker = verifiedRuntimeJvmWorker()
            result["jvm"] = mapOf("jdk" to identity(ToolComponent.JDK), "jvm-compiler" to worker.compiler,
                "jvm-worker" to worker.identity, "junit" to worker.junitIdentity)
            for (target in targets.filter { KotlinJsTarget.find(it) != null }) {
                val nodeIdentity = if (target in nodeTargets) mapOf("node" to requireNotNull(node) {
                    "JS/Wasm execution requires node.root and node.identity in tools: $target"
                }.second) else emptyMap()
                result[target] = result.getValue("jvm") + nodeIdentity
            }
            if ("jvm" !in targets) result.remove("jvm")
        }
        for (target in targets.mapNotNull(NativeTarget::find)) {
            val hostComponents = if (target.isApple)
                setOf(appleSdkComponent(target), ToolComponent.XCODE_TOOLCHAIN)
            else setOf(ToolComponent.SYSTEM, ToolComponent.NATIVE_TOOLCHAIN)
            require(roots.keys.containsAll(hostComponents)) { "${target.projectName} compilation requires its matching Native host" }
            val hostIdentity = if (target.isApple)
                "sdk" to identity(appleSdkComponent(target)) else "system" to identity(ToolComponent.SYSTEM)
            val dependencies = if (target.isApple)
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
    fun nodeProvider() = NodeToolchainProvider { expected, checkpoint ->
        val (root, identity) = requireNotNull(node) { "JS/Wasm execution requires node.root and node.identity in tools" }
        InstalledNodeToolchainProvider(root, identity).verified(expected, checkpoint)
    }
    fun nativeProvider(target: NativeTarget = if (ToolComponent.SYSTEM in roots) NativeTarget.LINUX_X64 else NativeTarget.MACOS_ARM64) =
        InstalledNativeToolchainProvider(lock, roots, data, target)
}
