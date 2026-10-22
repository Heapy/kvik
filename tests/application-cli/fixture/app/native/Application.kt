@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package fixture

import kotlinx.cinterop.*
import platform.posix.*

private fun environment(name: String) = getenv(name)?.toKString()
private fun readText(path: String): String = memScoped {
    val file = fopen(path, "r") ?: return@memScoped ""
    try {
        val buffer = allocArray<ByteVar>(4096)
        buildString { while (fgets(buffer, 4096, file) != null) append(buffer.toKString()) }
    } finally { fclose(file) }
}
private fun writeText(path: String, value: String, append: Boolean = false) {
    val file = checkNotNull(fopen(path, if (append) "a" else "w"))
    try { check(fputs(value, file) >= 0); check(fflush(file) == 0) } finally { fclose(file) }
}

// The declared entry point deliberately is not named main.
fun launchApplication(args: Array<String>) {
    check(args.take(5) == listOf("@literal.args", "", "space value", "snowman ☃", "--dash"))
    check(environment("AMBIENT_APPLICATION_POISON") == null && environment("JAVA_TOOL_OPTIONS") == null)
    check(environment("APP_DECLARED") in setOf("first", "second"))
    check(environment("PATH") == "/usr/bin:/bin:/usr/sbin:/sbin")
    check(environment("LANG") == "C" && environment("LC_ALL") == "C" && environment("TZ") == "UTC")
    check(getchar() == EOF)
    val cwd = memScoped { val buffer = allocArray<ByteVar>(4096); checkNotNull(getcwd(buffer, 4096u)).toKString() }
    check(cwd == environment("EXPECTED_CWD"))
    val inputs = checkNotNull(environment("KVIK_APPLICATION_INPUTS"))
    check(readText("$inputs/runtime/app/main/ordered.txt").trim() == "app-first")
    check(readText("$inputs/runtime/support/main/runtime.txt").trim() == "runtime-resource")
    check(libraryValue() == "runtime-external")
    println("args=${literalArguments(args)}\nenv=${environment("APP_DECLARED")}\ncwd=$cwd\nruntime=${libraryValue()}\nresource=runtime-resource")
    fputs("APPLICATION_STDERR_macosArm64\n", stderr)
    writeText("effects-macosArm64.log", "${getpid()}:${literalArguments(args)}:${environment("APP_DECLARED")}\n", true)
    val mode = readText("mode-macosArm64.txt").trim()
    if (mode == "nonzero") exit(7)
    if (mode.startsWith("wait:")) {
        val tag = mode.substringAfter(':')
        check(tag.matches(Regex("[A-Za-z0-9-]+")))
        writeText("$tag-app.pid", getpid().toString())
        fflush(null)
        val child = fork()
        check(child >= 0)
        if (child == 0) {
            writeText("$tag-child.pid", getpid().toString())
            val grandchild = fork()
            check(grandchild >= 0)
            if (grandchild == 0) writeText("$tag-grandchild.pid", getpid().toString())
        }
        while (true) sleep(1u)
    }
}
