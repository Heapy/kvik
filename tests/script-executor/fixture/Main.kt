package fixture

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.first()) {
        "ordinary" -> {
            check(System.`in`.read() == -1)
            check(System.getenv("JAVA_TOOL_OPTIONS") == null)
            check(System.getenv("KVIK_TEST_AMBIENT") == null)
            check(System.getenv("DECLARED") == "value")
            check(File("resource.txt").readText() == "working-directory-resource")
            File("effects.txt").appendText("effect\n")
            println(args.drop(1).joinToString("|"))
            System.err.println("separate-stderr ☃ 🚀")
            println(listOf("uses", "pinned", "stdlib").joinToString(" "))
        }
        "exit" -> exitProcess(args[1].toInt())
        "child", "wait" -> {
            val child = ProcessBuilder("/bin/sh", "-c", "trap '' TERM; /bin/sleep 600 & echo \$! > grandchild.pid; wait").start()
            File("child.pid").writeText(child.pid().toString())
            while (!File("grandchild.pid").exists()) Thread.sleep(10)
            File("ready").writeText("ready")
            if (args[0] == "wait") while (true) Thread.sleep(100)
        }
        else -> error("Unknown fixture mode")
    }
}
