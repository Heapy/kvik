@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kotlinx.cinterop.*
import platform.posix.*
import kvik.cache.NativeFiles

/** The pinned iOS framework layout is flat. Validate before publishing any output bytes to CAS. */
internal fun requireNativeFrameworkOutput(compilation: NativeCompilation, directory: String) {
    val root = "$directory/${compilation.output.value}"
    val name = compilation.output.value.substringAfterLast('/').removeSuffix(".framework")
    fun directory(path: String, entries: Set<String>? = null) {
        NativeFiles.ensureDirectory(path, create = false)
        if (entries != null) require(NativeFiles.names(path).toSet() == entries) { "Invalid framework directory: $path" }
    }
    fun file(path: String, executable: Boolean? = null) {
        val info = NativeFiles.info(path) ?: error("Missing framework file: $path")
        require(NativeFiles.regular(info) && info.size > 0 &&
            (executable == null || (info.mode and 73 != 0) == executable)) { "Invalid framework file: $path" }
    }
    directory(root, setOf(name, "Headers", "Modules", "Info.plist"))
    directory("$root/Headers", setOf("$name.h"))
    directory("$root/Modules", setOf("module.modulemap"))
    file("$root/Headers/$name.h", false)
    file("$root/Modules/module.modulemap", false)
    file("$root/Info.plist", false)
    file("$root/$name", !compilation.isStaticFramework)
    if (compilation.isStaticFramework) {
        require(frameworkPrefix("$root/$name", 8).contentEquals("!<arch>\n".encodeToByteArray())) { "Static framework binary must be an archive" }
    } else {
        requireArm64MachO("$root/$name", 6) // MH_DYLIB
        val symbols = "$root.dSYM"
        directory(symbols)
        directory("$symbols/Contents")
        directory("$symbols/Contents/Resources")
        directory("$symbols/Contents/Resources/DWARF", setOf(name))
        file("$symbols/Contents/Info.plist", false)
        file("$symbols/Contents/Resources/DWARF/$name")
        requireArm64MachO("$symbols/Contents/Resources/DWARF/$name", 10) // MH_DSYM
    }
}

private fun requireArm64MachO(path: String, type: Int) {
    val header = frameworkPrefix(path, 16)
    val expected = byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte(), 12, 0, 0, 1)
    require(header.take(8).toByteArray().contentEquals(expected) && header[12].toInt() == type &&
        header.sliceArray(13..15).all { it == 0.toByte() }) { "Invalid arm64 framework Mach-O type: $path" }
}

private fun frameworkPrefix(path: String, size: Int): ByteArray = memScoped {
    val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
    check(fd >= 0) { "Cannot read framework file: $path" }
    try {
        val info = alloc<stat>()
        require(fstat(fd, info.ptr) == 0 && info.st_mode.toInt() and S_IFMT == S_IFREG && info.st_size >= size) { "Invalid framework file: $path" }
        ByteArray(size).also { bytes -> bytes.usePinned { pinned ->
            var offset = 0
            while (offset < size) {
                val count = read(fd, pinned.addressOf(offset), (size - offset).toULong())
                if (count < 0 && errno == EINTR) continue
                check(count > 0) { "Cannot read framework prefix: $path" }
                offset += count.toInt()
            }
        } }
    } finally { close(fd) }
}
