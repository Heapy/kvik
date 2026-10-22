package acceptance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.Base64
import kotlin.system.exitProcess

private fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun hold(tag: String, child: Boolean) {
    Files.writeString(Path.of("$tag.pid"), ProcessHandle.current().pid().toString())
    Runtime.getRuntime().addShutdownHook(Thread { Thread.sleep(60_000) })
    if (child) {
        ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "--disable-@files",
            "-cp", System.getProperty("java.class.path"), "acceptance.MainKt", "grandchild")
            .inheritIO().start()
    }
    while (true) Thread.sleep(100)
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "child" -> { hold("child", true); return }
        "grandchild" -> { hold("grandchild", false); return }
    }
    Files.writeString(Path.of("effects.log"), args.first() + "\t" + ProcessHandle.current().pid() + "\n", CREATE, APPEND)
    System.out.write("SCRIPT_STDOUT\u0000\n".toByteArray())
    args.forEachIndexed { index, value -> println("arg.$index=" + encoded(value)) }
    println("cwd=" + encoded(Path.of("").toRealPath().toString()))
    println("declared=" + encoded(System.getenv("SCRIPT_DECLARED") ?: "missing"))
    println("ambient=" + encoded(System.getenv("AMBIENT_SCRIPT_POISON") ?: "absent"))
    println("java-options=" + encoded(System.getenv("JAVA_TOOL_OPTIONS") ?: "absent"))
    println("stdin=" + System.`in`.read())
    println("stdlib=" + listOf(1, 2, 3).joinToString(","))
    println("unicode=☃ 🚀")
    System.err.write("SCRIPT_STDERR\u0000\n".toByteArray())
    System.err.println("unicode=☃ 🚀")
    System.out.flush(); System.err.flush()
    when (val mode = args.first()) {
        "wait" -> {
            Files.writeString(Path.of("app.pid"), ProcessHandle.current().pid().toString())
            Runtime.getRuntime().addShutdownHook(Thread { Thread.sleep(60_000) })
            ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "--disable-@files",
                "-cp", System.getProperty("java.class.path"), "acceptance.MainKt", "child").inheritIO().start()
            while (true) Thread.sleep(100)
        }
        "ok" -> Unit
        else -> exitProcess(mode.removePrefix("exit-").toInt())
    }
}
