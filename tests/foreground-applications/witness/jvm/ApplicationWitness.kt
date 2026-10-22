package fixture.witness

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Acceptance-only witness, executed before the unchanged application's first effect. */
fun main(args: Array<String>) {
    val path = Path.of("previous-ownership-jvm.txt")
    val previous = if (Files.exists(path)) Files.readString(path) else ""
    check(previous.length <= 4096)
    if (previous.isNotEmpty()) {
        val process = ProcessBuilder("/bin/ps", "-axo", "pid=,pgid=").start()
        val rows = process.inputStream.bufferedReader().readLines().map { it.trim().split(Regex("\\s+")).map(String::toLong) }
        check(process.waitFor() == 0)
        for (line in previous.lineSequence().filter(String::isNotEmpty)) {
            val parts = line.split(' '); check(parts.size == 2)
            val value = parts[1].toLong(); check(value > 1)
            when (parts[0]) {
                "pid" -> check(rows.none { it[0] == value }) { "Prior owned process overlaps replacement: $value" }
                "group" -> check(rows.none { it[1] == value }) { "Prior owned process group overlaps replacement: $value" }
                else -> error("Invalid witness input")
            }
        }
    }
    Files.writeString(Path.of("witness-jvm.log"), previous.replace('\n', ';') + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    fixture.main(args)
}
