package kvik.bsp

import kvik.core.*

/** Bounded connection-local history of diagnostics observed for exact successful action keys. */
class BspDiagnosticReplay(private val maxEntries: Int = 256, private val maxBytes: Long = 8L * 1024 * 1024) {
    init { require(maxEntries > 0 && maxBytes > 0) }
    private data class Entry(val values: List<Diagnostic>, val bytes: Long)
    private val entries = linkedMapOf<Pair<String, Digest>, Entry>()
    private var bytes = 0L

    fun record(actionId: String, key: Digest, diagnostics: List<Diagnostic>) {
        val identity = actionId to key
        entries.remove(identity)?.let { bytes -= it.bytes }
        val size = diagnostics.sumOf { it.toJson().canonical().encodeToByteArray().size.toLong() }
        if (size > maxBytes) return
        while (entries.isNotEmpty() && (entries.size >= maxEntries || bytes > maxBytes - size)) {
            bytes -= entries.remove(entries.keys.first())!!.bytes
        }
        entries[identity] = Entry(diagnostics.toList(), size)
        bytes += size
    }

    fun find(actionId: String, key: Digest): List<Diagnostic>? {
        val identity = actionId to key
        val entry = entries.remove(identity) ?: return null
        entries[identity] = entry
        return entry.values.toList()
    }
}
