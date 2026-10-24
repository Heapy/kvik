package kvik.compilertools

import kvik.cache.NativeFiles
import kvik.compiler.jvm.JvmToolchainLayout
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.core.*
import kvik.toolchains.*

/** Caller-trusted pins and stable installations; every successful call freshly verifies raw bytes. */
class InstalledJvmToolchainProvider(
    private val trustedToolLock: String,
    toolRoots: Map<ToolComponent, String>,
    private val bundleRoot: String,
    private val expectedBundleManifest: Digest,
    private val compilerArtifactsRoot: String,
) : JvmToolchainProvider {
    private val toolRoots = toolRoots.toMap()

    override fun verified(identities: Map<String, Digest>): JvmToolchainLayout = verified(identities) {}

    override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout =
        verifyLayout(identities, checkpoint, compiler = true)

    override fun verifiedExecution(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout =
        verifyLayout(identities, checkpoint, compiler = false)

    private fun verifyLayout(identities: Map<String, Digest>, checkpoint: () -> Unit, compiler: Boolean): JvmToolchainLayout {
        checkpoint()
        val requested = identities.toMap()
        require(requested.keys == setOf("jdk", "jvm-compiler", "jvm-worker", "junit")) { "JVM tool identities require exactly jdk, jvm-compiler, jvm-worker and junit" }
        val bundle = verifyJvmWorkerBundle(bundleRoot, expectedBundleManifest, checkpoint)
        require(requested.getValue("jvm-compiler") == bundle.compiler && requested.getValue("jvm-worker") == bundle.identity &&
            requested.getValue("junit") == bundle.junitIdentity) { "Requested JVM identities differ from verified worker bundle" }
        if (compiler) {
            NativeFiles.ensureDirectory(compilerArtifactsRoot, create = false)
            // Identity is canonical JSON in the worker protocol. The source-built bundle also pins raw bytes.
            val manifest = parseJson(NativeFiles.read(bundle.compilerManifest, 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
            checkpoint()
            require(Sha256.digest(manifest) == bundle.compiler) { "Compiler manifest is not the pinned canonical document" }
            val fields = manifest.fields(setOf("schemaVersion", "version", "artifacts", "serializationPlugin"))
            fields.requireVersion()
            require(fields.getValue("version").stringValue() == "2.4.20") { "Unsupported JVM compiler distribution version" }
            val artifacts = fields.getValue("artifacts").arrayValue()
            require(artifacts.size in 1..128) { "Compiler distribution requires 1..128 jars" }
            val names = mutableSetOf<String>()
            var bytes = 0L
            fun verifyJar(name: String, expected: Digest) {
                checkpoint()
                require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.jar")) && names.add(name)) { "Unsafe or duplicate compiler jar path: $name" }
                require(expected.sizeBytes <= 128L * 1024 * 1024) { "Compiler jar exceeds 128 MiB" }
                bytes += expected.sizeBytes
                require(bytes <= 256L * 1024 * 1024) { "Compiler distribution exceeds 256 MiB" }
                require(CompilerFiles.digest("$compilerArtifactsRoot/$name", expected.sizeBytes, checkpoint) == expected) { "Compiler jar differs from manifest: $name" }
            }
            for (value in artifacts) {
                val entry = value.fields(setOf("path", "digest"))
                verifyJar(entry.getValue("path").stringValue(), Digest.fromJson(entry.getValue("digest")))
            }
            // Existing distribution schema pins this supported plugin by digest, without a path field.
            val plugin = fields.getValue("serializationPlugin")
            if (plugin != JsonValue.Null) verifyJar("kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar", Digest.fromJson(plugin))
        }
        val jdk = InstalledToolchainVerifier().verifyJdk(trustedToolLock, toolRoots.getValue(ToolComponent.JDK), checkpoint)
        require(requested.getValue("jdk") == jdk.digest) { "Requested JDK identity differs from verified installation" }
        val java = "${jdk.root}/bin/java"
        NativeFiles.ensureDirectory("${jdk.root}/bin", create = false)
        val javaInfo = NativeFiles.info(java) ?: error("Missing verified JDK java executable")
        require(NativeFiles.regular(javaInfo) && javaInfo.mode and 73 != 0) { "Verified JDK java must be a regular executable" }
        checkpoint()
        return JvmToolchainLayout(requested, java, bundle.classes, bundle.api, bundle.stdlib, bundle.junit,
            compilerArtifactsRoot, bundle.compilerManifest, jacocoAgent = bundle.jacocoAgent, jacocoCli = bundle.jacocoCli,
            jacocoAgentIdentity = bundle.jacocoAgentIdentity, jacocoCliIdentity = bundle.jacocoCliIdentity)
    }
}
