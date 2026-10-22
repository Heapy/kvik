@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package audit

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import kotlinx.cinterop.*
import platform.posix.*

private fun scenario(): String = memScoped {
    val file = fopen("input/scenario.txt", "r") ?: error("Missing declared scenario input")
    try {
        val buffer = allocArray<ByteVar>(128)
        check(fgets(buffer, 128, file) != null)
        buffer.toKString().trim()
    } finally { fclose(file) }
}

private fun effect(): Unit {
    val file = fopen("effects.txt", "a") ?: error("Cannot write fixture execution effect")
    try { check(fputs("leaf\n", file) >= 0) } finally { fclose(file) }
}

public class AuditProjectConfig : AbstractProjectConfig() {
    override suspend fun beforeProject(): Unit {
        if (scenario() == "engine-error") error("expected engine startup failure")
    }
}

public class LifecycleSpec : FunSpec({
    val mode = scenario()
    if (mode != "zero") {
        test("passing effect").config(enabled = mode != "all-skipped") { effect() }
        test("ordinary assertion").config(enabled = mode != "all-skipped") {
            if (mode == "fail") throw AssertionError("expected leaf assertion failure")
            if (mode == "error") error("expected leaf error")
        }
        test("skipped leaf").config(enabled = false) { error("disabled leaf executed") }
        context("nested.container").config(enabled = mode != "ignored-container" && mode != "all-skipped") {
            test("nested.leaf") { }
        }
        test("process failure").config(enabled = mode != "all-skipped") {
            if (mode == "crash") kotlin.system.exitProcess(17)
            if (mode == "hang") while (true) usleep(10_000u)
        }
    }
})
