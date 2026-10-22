package fixture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    check(args.take(5) == listOf("@literal.args", "", "space value", "snowman ☃", "--dash"))
    check(System.getenv("AMBIENT_APPLICATION_POISON") == null)
    check(System.getenv("JAVA_TOOL_OPTIONS") == null)
    check(System.getenv("APP_DECLARED") in setOf("first", "second"))
    check(System.getenv("PATH") == "/usr/bin:/bin:/usr/sbin:/sbin")
    check(System.getenv("LANG") == "C" && System.getenv("LC_ALL") == "C" && System.getenv("TZ") == "UTC")
    check(System.`in`.read() == -1)
    val cwd = Path.of("").toAbsolutePath().normalize().toString()
    check(cwd == System.getenv("EXPECTED_CWD"))
    val loader = Thread.currentThread().contextClassLoader
    fun resource(name: String) = loader.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }.trim()
    check(resource("ordered.txt") == "app-first")
    check(resource("runtime.txt") == "runtime-resource")
    val reflected = Class.forName("support.SupportKt").getMethod("runtimeValue").invoke(null)
    check(reflected == "runtime-external" && libraryValue() == reflected)
    val pid = ProcessHandle.current().pid()
    val output = "args=${literalArguments(args)}\nenv=${System.getenv("APP_DECLARED")}\ncwd=$cwd\nruntime=$reflected\nresource=runtime-resource\n"
    print(output)
    System.err.println("APPLICATION_STDERR_jvm")
    Files.writeString(Path.of("effects-jvm.log"), "$pid:${literalArguments(args)}:${System.getenv("APP_DECLARED")}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    val mode = Path.of("mode-jvm.txt").takeIf(Files::exists)?.let(Files::readString)?.trim().orEmpty()
    if (mode == "nonzero") exitProcess(7)
    if (mode.startsWith("wait:")) {
        val tag = mode.substringAfter(':')
        check(tag.matches(Regex("[A-Za-z0-9-]+")))
        Files.writeString(Path.of("$tag-app.pid"), pid.toString())
        val child = ProcessBuilder("/bin/sh", "-c", "echo \$\$ > $tag-child.pid; /bin/sleep 300 & echo \$! > $tag-grandchild.pid; wait").inheritIO().start()
        child.waitFor()
        while (true) Thread.sleep(1000)
    }
}
