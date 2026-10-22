@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.spike

import kotlinx.cinterop.*
import kvik.spike.platform.*

/** Kotlin/Native source plugin exercising the platform interop boundary. */
class ProbePlugin : SpikePlugin {
    override val id = "k01-platform-probe"
    override fun probe(): Map<String, String> = memScoped {
        val buffer = allocArray<ByteVar>(4096)
        check(kvik_temp_directory(buffer, 4096.convert()) == 0)
        val directory = buffer.toKString()
        val input = "$directory/input.txt"
        val download = "$directory/stdlib.pom"
        try {
            val watcher = checkNotNull(kvik_watch_open(directory))
            try {
                check(kvik_write_text(input, "kvik-source-plugin\n") == 0)
                check(kvik_watch_wait(watcher, 10) == 1) { "File watcher produced no event" }
            } finally { kvik_watch_close(watcher) }
            check(kvik_sha256(input, buffer) == 0)
            val digest = buffer.toKString()
            check(digest == "e0a7bd232cdb16bda486b6f67641b5e2e30a9dc1a733529000894bf8e24e631e")
            val status = kvik_fetch(
                "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.20/kotlin-stdlib-2.4.20.pom", download,
            )
            check(status == 200L) { "Maven HTTPS status: $status" }
            check(kvik_sha256(download, buffer) == 0)
            mapOf("plugin" to id, "sha256" to digest, "watcher" to "passed", "https" to "passed", "downloadSha256" to buffer.toKString())
        } finally {
            kvik_remove(input)
            kvik_remove(download)
            kvik_remove(directory)
        }
    }
}
