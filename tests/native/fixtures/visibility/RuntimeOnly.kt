@file:OptIn(kotlin.ExperimentalStdlibApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
package runtimeonly
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fclose
@kotlin.native.EagerInitialization
private val startup = run {
    val output = checkNotNull(fopen("runtime-only.effect", "a"))
    check(fputs("initialized\n", output) >= 0)
    check(fclose(output) == 0)
    1
}
public fun runtimeOnlyApi(): Int = 999
