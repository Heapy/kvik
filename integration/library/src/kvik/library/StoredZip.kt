package kvik.library

import kvik.core.*

/** ZIP32 stored entries: UTF-8 names, fixed DOS 1980-01-01 timestamps, no extras/comments/descriptors. */
internal fun storedZipSize(entries: List<FileEntry>, includeDirectories: Boolean = false): Int = zipSize(zipEntries(entries, includeDirectories))

private data class ZipEntry(val name: String, val size: Long, val mode: Int, val directory: Boolean = false)

private fun zipEntries(files: List<FileEntry>, includeDirectories: Boolean): List<ZipEntry> {
    val entries = files.map { ZipEntry(it.path.value, it.digest.sizeBytes, if (it.executable) 0x81ed else 0x81a4) }
    if (!includeDirectories) return entries
    require(files.map { it.path.value } == files.map { it.path.value }.sorted()) { "ZIP files must be sorted when including directories" }
    val directories = files.flatMap { file ->
        file.path.value.indices.filter { file.path.value[it] == '/' }.map { file.path.value.substring(0, it + 1) }
    }.distinct().map { ZipEntry(it, 0, 0x41ed, true) }
    return (entries + directories).sortedBy { it.name }
}

private fun zipSize(entries: List<ZipEntry>): Int {
    require(entries.size <= 10001)
    var size = 22L
    for (entry in entries) {
        val length = entry.name.encodeToByteArray().size
        require(length in 1..65535) { "ZIP filename exceeds 65535 UTF-8 bytes" }
        require(entry.size <= LIBRARY_BYTE_LIMIT)
        size += 76L + 2L * length + entry.size
        require(size <= LIBRARY_BYTE_LIMIT) { "Library archive exceeds 64 MiB" }
    }
    return size.toInt()
}

internal class StoredZip(files: List<FileEntry>, includeDirectories: Boolean = false) {
    private val entries = zipEntries(files, includeDirectories)
    private val bytes = ByteArray(zipSize(entries))
    private var offset = 0
    private var cursor = 0
    private val checksums = IntArray(entries.size)
    private val positions = IntArray(entries.size)
    private fun short(value: Int) { bytes[offset++] = value.toByte(); bytes[offset++] = (value ushr 8).toByte() }
    private fun integer(value: Int) { short(value); short(value ushr 16) }
    private fun data(value: ByteArray) { value.copyInto(bytes, offset); offset += value.size }

    fun append(value: ByteArray, checkpoint: () -> Unit) {
        while (cursor < entries.size && entries[cursor].directory) {
            checkpoint()
            appendEntry(byteArrayOf(), checkpoint)
        }
        check(cursor < entries.size)
        appendEntry(value, checkpoint)
    }

    private fun appendEntry(value: ByteArray, checkpoint: () -> Unit) {
        val entry = entries[cursor]
        require(value.size.toLong() == entry.size)
        val name = entry.name.encodeToByteArray()
        val crc = crc32(value, checkpoint)
        positions[cursor] = offset; checksums[cursor] = crc
        integer(0x04034b50); short(20); short(0x800); short(0); short(0); short(33)
        integer(crc); integer(value.size); integer(value.size); short(name.size); short(0)
        data(name); data(value); cursor++
    }

    fun finish(checkpoint: () -> Unit): ByteArray {
        check(cursor == entries.size)
        val central = offset
        entries.forEachIndexed { index, entry ->
            checkpoint()
            val name = entry.name.encodeToByteArray()
            integer(0x02014b50); short(0x0314); short(20); short(0x800); short(0); short(0); short(33)
            integer(checksums[index]); integer(entry.size.toInt()); integer(entry.size.toInt())
            short(name.size); short(0); short(0); short(0); short(0)
            integer((entry.mode shl 16) or if (entry.directory) 0x10 else 0); integer(positions[index]); data(name)
        }
        val centralSize = offset - central
        integer(0x06054b50); short(0); short(0); short(entries.size); short(entries.size)
        integer(centralSize); integer(central); short(0)
        check(offset == bytes.size)
        return bytes
    }
}

private val crcTable = IntArray(256) { index ->
    var value = index
    repeat(8) { value = (value ushr 1) xor if ((value and 1) != 0) 0xedb88320.toInt() else 0 }
    value
}
private fun crc32(bytes: ByteArray, checkpoint: () -> Unit): Int {
    var crc = -1
    bytes.forEachIndexed { index, byte ->
        if (index % (256 * 1024) == 0) checkpoint()
        crc = (crc ushr 8) xor crcTable[(crc xor byte.toInt()) and 255]
    }
    return crc.inv()
}
