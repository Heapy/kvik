@file:OptIn(kotlinx.validation.ExperimentalBCVApi::class)
package kvik.jvm

import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import kotlinx.validation.api.klib.KlibDump
import java.io.Writer
import java.nio.file.Path
import java.util.jar.JarFile

/** Loaded only inside the verified compiler distribution, never with consumer classes. */
object AbiReader {
    @JvmStatic fun read(artifact: String, target: String, compilerVersion: String): String {
        val version = Class.forName("org.jetbrains.kotlin.config.KotlinCompilerVersion")
            .getField("VERSION").get(null)
        require(kvik.core.isSupportedJvmCompilerVersion(compilerVersion) && version == compilerVersion) {
            "ABI reader compiler version does not match manifest: expected $compilerVersion, found $version"
        }
        require(compilerVersion != "2.4.21" || target == "jvm") { "Kotlin 2.4.21 ABI extraction supports JVM only" }
        val output = BoundedText()
        if (target == "jvm") {
            JarFile(artifact).use { it.loadApiFromJvmClasses() }.filterOutNonPublic().dump(output)
        } else {
            KlibDump.fromKlib(Path.of(artifact).toFile(), configurableTargetName = target).saveTo(output)
        }
        return output.toString().replace("\r\n", "\n").also { require('\r' !in it && '\u0000' !in it) }
    }

    private class BoundedText : Writer() {
        private val text = StringBuilder()
        override fun write(value: CharArray, offset: Int, length: Int) {
            require(text.length.toLong() + length <= 8 * 1024 * 1024) { "ABI dump exceeds 8 MiB" }
            text.append(value, offset, length)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString() = text.toString()
    }
}
