package kvik.compilertools

import kvik.cache.NativeFiles
import kvik.compiler.js.NodeToolchainProvider
import kvik.core.Digest
import kvik.toolchains.inventory

/** Pin a complete standalone Node installation, using the same inventory format as other tools. */
class InstalledNodeToolchainProvider(private val root: String, private val expected: Digest) : NodeToolchainProvider {
    init { NativeFiles.validateAbsolute(root) }

    override fun verified(identity: Digest, checkpoint: () -> Unit): String {
        require(identity == expected) { "Desired Node identity changed" }
        checkpoint()
        NativeFiles.ensureDirectory(root, create = false)
        require(inventory(root, checkpoint = checkpoint).digest == expected) { "Node installation differs from its pinned inventory" }
        NativeFiles.ensureDirectory("$root/bin", create = false)
        val executable = "$root/bin/node"
        val info = NativeFiles.info(executable) ?: error("Missing verified Node executable")
        require(NativeFiles.regular(info) && info.mode and 73 != 0) { "Node bin/node must be a regular executable" }
        checkpoint()
        return executable
    }
}
