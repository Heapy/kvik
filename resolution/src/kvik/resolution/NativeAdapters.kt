@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.resolution

import kotlinx.cinterop.*
import kvik.core.ArtifactTransport
import kvik.resolution.native.*

class NativeHttpsTransport(private val maxBytes: Int = 64 * 1024 * 1024, private val timeoutSeconds: Int = 30) : ArtifactTransport {
    init { require(maxBytes in 1..256 * 1024 * 1024 && timeoutSeconds in 1..120) }
    override fun fetch(url: String): ByteArray {
        validateHttpsUrl(url)
        val response = kvik_fetch(url, maxBytes.toULong(), timeoutSeconds.toLong()) ?: error("Transport allocation failed")
        try {
            val r = response.pointed
            check(r.error == 0) { "HTTPS transfer failed or exceeded bounds (${r.error}): $url" }
            if (r.status == 404L) throw MissingResource(url)
            check(r.status == 200L) { "HTTP ${r.status}: $url" }
            return if (r.size == 0UL) byteArrayOf() else r.data!!.reinterpret<ByteVar>().readBytes(r.size.toInt())
        } finally { kvik_response_free(response) }
    }
}

/** libxml2 parses XML, with network/DTD/entity substitution disabled and a bounded Kotlin tree. */
class NativePomParser : PomParser {
    override fun parse(bytes: ByteArray): PomNode {
        require(bytes.size in 1..1024 * 1024) { "POM exceeds byte bound" }
        bytes.decodeToString(throwOnInvalidSequence = true)
        return bytes.usePinned { pinned ->
            val document = kvik_xml(pinned.addressOf(0), bytes.size) ?: error("Invalid POM XML or forbidden DTD")
            try {
                var count = 0
                fun node(pointer: xmlNodePtr, depth: Int): PomNode {
                    require(depth <= 64 && ++count <= 10000) { "POM exceeds structural bounds" }
                    require(kvik_ns(pointer)!!.toKString() in setOf("", "http://maven.apache.org/POM/4.0.0")) { "Foreign POM namespace" }
                    val name = kvik_name(pointer)!!.toKString()
                    val children = mutableListOf<PomNode>()
                    var child = kvik_child(pointer)
                    while (child != null) { children += node(child, depth + 1); child = kvik_next(child) }
                    val content = if (children.isEmpty()) {
                        val text = kvik_text(pointer)
                        try { text?.toKString()?.trim().orEmpty() } finally { kvik_text_free(text) }
                    } else ""
                    return PomNode(name, content, children)
                }
                node(kvik_root(document) ?: error("Empty POM"), 0)
            } finally { kvik_xml_free(document) }
        }
    }
}
