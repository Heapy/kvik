@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cache.tests

import kvik.cache.*
import kvik.core.*
import platform.posix.*

fun main(args: Array<String>) {
    require(args.size >= 2)
    val root = args[1]
    val report: (CacheDiagnostic) -> Unit = { println("DIAGNOSTIC ${it.code}: ${it.message}") }
    val store = NativeContentStore("$root/cas", diagnostic = report)
    val cache = NativeActionCache("$root/actions", store, report)
    val trees = NativeOutputTrees(store)
    when (args[0]) {
        "put" -> {
            val digest = store.put(args[2].encodeToByteArray())
            println("DIGEST ${digest.hash} ${digest.sizeBytes}")
        }
        "get" -> {
            val bytes = store.get(Digest(args[2], args[3].toLong()))
            println(if (bytes == null) "MISS" else "HIT ${bytes.decodeToString()}")
        }
        "capture" -> {
            val manifest = trees.capture(args[2])
            println("MANIFEST ${manifest.toJson().canonical()}")
        }
        "publish" -> {
            val key = Sha256.digest(args[2].encodeToByteArray())
            val manifest = TreeManifest.fromJson(parseJson(args[3]))
            cache.publish(CachedAction(key, manifest))
            println("PUBLISHED")
        }
        "lookup" -> {
            val record = cache.lookup(Sha256.digest(args[2].encodeToByteArray()))
            println(if (record == null) "MISS" else "HIT ${record.outputs.toJson().canonical()}")
        }
        "restore" -> {
            trees.materialize(TreeManifest.fromJson(parseJson(args[2])), args[3])
            println("RESTORED")
        }
        "interrupted", "interrupted-action" -> {
            // Same native fsync/staging path as publication, deliberately stopped before the link.
            val blob = args[0] == "interrupted"
            val key = Sha256.digest((if (blob) "unfinished" else "unfinished-action").encodeToByteArray())
            val locks = if (blob) "$root/cas/locks" else "$root/actions/locks"
            NativeFiles.withLock("$locks/${key.hash}-${key.sizeBytes}.lock") {
                val stage = NativeFiles.tempDirectory(if (blob) "$root/cas/blobs" else "$root/actions")
                NativeFiles.writeNew("$stage/content", "unfinished".encodeToByteArray())
                println("READY")
                fflush(stdout)
                while (true) sleep(1u)
            }
        }
        else -> error("Unknown operation ${args[0]}")
    }
}
