package kvik.cli

import kvik.cache.NativeFiles
import kvik.cache.remote.*
import kvik.core.*

/** Transport settings never enter action identities; credentials are read only when online. */
internal class RemoteCacheConfiguration(document: JsonValue) {
    private val fields = document.fields(setOf("schemaVersion", "url", "trustedWriters"),
        setOf("provider", "instance", "mode", "tokenEnvironment", "username"))
    val provider: String
    val url: String
    val instance: String
    val write: Boolean
    val tokenEnvironment: String?
    val username: String?
    // Kotlin runtime jars commonly exceed the protocol client's conservative 1 MiB default.
    val limits = TransferLimits(blobBytes = 16 * 1024 * 1024, rpcBytes = 32 * 1024 * 1024,
        totalBytes = 128L * 1024 * 1024)
    init {
        fields.requireVersion()
        provider = fields["provider"]?.stringValue() ?: "reapi"
        require(provider in setOf("reapi", "gradle-http")) { "Unknown remote cache provider" }
        url = fields.getValue("url").stringValue()
        instance = fields["instance"]?.stringValue() ?: ""
        require(fields.getValue("trustedWriters").booleanValue()) { "Remote cache requires explicitly trusted writers" }
        write = when (fields["mode"]?.stringValue() ?: "read") {
            "read" -> false
            "read-write" -> true
            else -> error("Remote cache mode must be read or read-write")
        }
        tokenEnvironment = fields["tokenEnvironment"]?.stringValue()?.also {
            require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid remote cache token environment name" }
        }
        username = fields["username"]?.stringValue()
        // Validate transport configuration even in offline mode, without reading credentials.
        if (provider == "gradle-http") {
            require(tokenEnvironment != null && username != null) { "HTTP cache requires username and tokenEnvironment" }
            HttpCacheEndpoint(url, instance, username, trustedWriters = true)
        } else {
            require(username == null) { "REAPI uses bearer authentication, not username" }
            RemoteEndpoint(url, instance, trustedWriters = true)
        }
    }
    fun credential(environment: (String) -> String?): String? {
        val token = tokenEnvironment?.let { name -> environment(name)?.takeIf { it.isNotEmpty() } }
        if (provider == "reapi") {
            require(tokenEnvironment == null || token != null) { "Missing remote cache credential environment: $tokenEnvironment" }
            RemoteEndpoint(url, instance, token, trustedWriters = true)
        } else {
            HttpCacheEndpoint(url, instance, checkNotNull(username), token, trustedWriters = true)
        }
        return token
    }
    fun client(content: ContentStore, local: ActionCache, approvals: Set<PortabilityApproval>, token: String?): RemoteCacheClient =
        if (provider == "gradle-http") HttpActionCache(content,
            HttpCacheEndpoint(url, instance, checkNotNull(username), checkNotNull(token), trustedWriters = true), approvals, limits)
        else RemoteActionCache(content, local, RemoteEndpoint(url, instance, token, trustedWriters = true), approvals, limits)
    companion object {
        fun read(path: String) = RemoteCacheConfiguration(parseJson(
            NativeFiles.read(path, 64 * 1024).decodeToString(throwOnInvalidSequence = true)))
    }
}

internal fun remoteSessionCredentials(root: String, options: BuildOptions): Map<String, String> {
    if (options.project.offline) return emptyMap()
    // Configuration/credential errors belong to the operation's diagnostics, not the control loop.
    // The worker rereads and validates the complete configuration before using this environment.
    return try {
        val configuration = options.remoteCache?.let { RemoteCacheConfiguration.read(projectPath(root, it)) } ?: return emptyMap()
        val name = configuration.tokenEnvironment ?: return emptyMap()
        val token = boundedEnvironment(name, 8192) ?: return emptyMap()
        mapOf(name to token)
    } catch (_: Exception) { emptyMap() }
}

internal fun remoteOperationEnvironment(root: String, arguments: List<String>): Map<String, String> {
    val start = if (arguments.getOrNull(1) == "stage-application") 6 else 2
    val (source, remaining) = sourceProjectOptions(arguments.drop(start))
    val options = buildOptions(remaining)
    return remoteSessionCredentials(root, options) +
        if ((options.remoteCache != null || source != null) && !options.project.offline) mapOf("KVIK_BOOTSTRAP_OFFLINE" to "0") else emptyMap()
}
