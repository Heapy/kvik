package kvik.library

import kvik.core.*

/** ZIP32 stored entries: UTF-8 names, fixed DOS 1980-01-01 timestamps, no extras/comments/descriptors. */
internal fun storedZipSize(entries: List<FileEntry>): Int {
    require(entries.size <= 10001)
    var size = 22L
    for (entry in entries) {
        val length = entry.path.value.encodeToByteArray().size
        require(length in 1..65535) { "ZIP filename exceeds 65535 UTF-8 bytes" }
        require(entry.digest.sizeBytes <= LIBRARY_BYTE_LIMIT)
        size += 76L + 2L * length + entry.digest.sizeBytes
        require(size <= LIBRARY_BYTE_LIMIT) { "Library archive exceeds 64 MiB" }
    }
    return size.toInt()
}

internal class StoredZip(private val entries: List<FileEntry>) {
    private val bytes = ByteArray(storedZipSize(entries))
    private var offset = 0
    private var cursor = 0
    private val checksums = IntArray(entries.size)
    private val positions = IntArray(entries.size)
    private fun short(value: Int) { bytes[offset++] = value.toByte(); bytes[offset++] = (value ushr 8).toByte() }
    private fun integer(value: Int) { short(value); short(value ushr 16) }
    private fun data(value: ByteArray) { value.copyInto(bytes, offset); offset += value.size }

    fun append(value: ByteArray, checkpoint: () -> Unit) {
        val entry = entries[cursor]
        require(value.size.toLong() == entry.digest.sizeBytes)
        val name = entry.path.value.encodeToByteArray()
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
            val name = entry.path.value.encodeToByteArray()
            integer(0x02014b50); short(0x0314); short(20); short(0x800); short(0); short(0); short(33)
            integer(checksums[index]); integer(entry.digest.sizeBytes.toInt()); integer(entry.digest.sizeBytes.toInt())
            short(name.size); short(0); short(0); short(0); short(0)
            integer((if (entry.executable) 0x81ed else 0x81a4) shl 16); integer(positions[index]); data(name)
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
