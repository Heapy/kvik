@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package fixture.witness

import kotlinx.cinterop.*
import platform.posix.*

/** Acceptance-only witness, executed before the unchanged custom-entry application's first effect. */
fun launchWitness(args: Array<String>) {
    val previous = memScoped {
        val file = fopen("previous-ownership-macosArm64.txt", "r")
        if (file == null) "" else try {
            val buffer = allocArray<ByteVar>(4096)
            buildString { while (fgets(buffer, 4096, file) != null) { append(buffer.toKString()); check(length <= 4096) } }
        } finally { fclose(file) }
    }
    for (line in previous.lineSequence().filter(String::isNotEmpty)) {
        val parts = line.split(' '); check(parts.size == 2)
        val value = parts[1].toInt(); check(value > 1)
        val pid = when (parts[0]) { "pid" -> value; "group" -> -value; else -> error("Invalid witness input") }
        check(kill(pid, 0) == -1 && errno == ESRCH) { "Prior ownership overlaps replacement: $line" }
    }
    val log = checkNotNull(fopen("witness-macosArm64.log", "a"))
    try { check(fputs(previous.replace('\n', ';') + "\n", log) >= 0); check(fflush(log) == 0) } finally { fclose(log) }
    fixture.launchApplication(args)
}
