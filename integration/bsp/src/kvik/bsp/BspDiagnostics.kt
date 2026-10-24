package kvik.bsp

import kvik.core.*

/** Diagnostics from one producer; several producers may share a compilation target. */
data class BspActionDiagnostics(val target: BspTarget, val documents: Map<String, List<JsonValue>>)

/** Replace only observed producers, retaining diagnostics for blocked or unfinished actions. */
class BspDiagnostics {
    private val actions = linkedMapOf<String, BspActionDiagnostics>()
    private val published = linkedMapOf<Pair<BspTarget, String>, List<JsonValue>>()

    fun replace(updates: Map<String, BspActionDiagnostics>): List<JsonValue.Object> {
        actions.putAll(updates)
        return changes()
    }

    fun clearDocuments(target: BspTarget, uris: Set<String>): List<JsonValue.Object> {
        for (entry in actions.entries) if (entry.value.target == target) {
            entry.setValue(entry.value.copy(documents = entry.value.documents.filterKeys { it !in uris }))
        }
        return changes()
    }

    fun clear(): List<JsonValue.Object> {
        actions.clear()
        return changes()
    }

    private fun changes(): List<JsonValue.Object> {
        val current = linkedMapOf<Pair<BspTarget, String>, MutableList<JsonValue>>()
        for ((_, action) in actions) for ((uri, diagnostics) in action.documents) {
            if (diagnostics.isNotEmpty()) current.getOrPut(action.target to uri) { mutableListOf() }.addAll(diagnostics)
        }
        val merged = current.mapValues { it.value.distinct() }
        val changed = (published.keys + merged.keys).mapNotNull { key ->
            val next = merged[key].orEmpty()
            if (published[key].orEmpty() == next) null else obj(
                "textDocument" to obj("uri" to key.second.json()), "buildTarget" to obj("uri" to key.first.uri.json()),
                "diagnostics" to next.json(), "reset" to true.json())
        }
        published.clear()
        published.putAll(merged)
        return changed
    }
}
