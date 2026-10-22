@file:JvmName("ScriptEntry")
package fixture

private val initialization = java.io.File("unexpected-initialization").writeText("must not run during discovery")

fun main(args: Array<String>) { println(args.joinToString()) }

class Helper {
    fun main(args: Array<String>) { println(args.size) }
}
