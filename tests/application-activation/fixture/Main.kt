package fixture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

fun main(args: Array<String>) {
    check(args.toList() == listOf("@literal", "space value", ""))
    check(System.getenv("JAVA_TOOL_OPTIONS") == null)
    check(System.`in`.read() == -1)
    val pid = ProcessHandle.current().pid()
    Files.writeString(Path.of("effects.log"), "$pid\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    if (Files.exists(Path.of("wait.txt"))) {
        Files.writeString(Path.of("app.pid"), pid.toString())
        val child = ProcessBuilder("/bin/sh", "-c", "echo \$\$ > child.pid; /bin/sleep 300 & echo \$! > grandchild.pid; wait").inheritIO().start()
        child.waitFor()
    }
}
