package kvik.cache.remote

import kvik.core.*

class RemoteFailure(message: String) : Exception(message)
internal fun demand(condition: Boolean, message: String) { if (!condition) throw RemoteFailure(message) }

/** Restricted protobuf codec: bounded messages, canonical emitted scalars, unknown fields retained. */
internal class ProtoWriter {
    private var buffer = ByteArray(128)
    private var size = 0
    private fun append(value: Byte) { reserve(1); buffer[size++] = value }
    private fun reserve(count: Int) {
        demand(count >= 0 && size.toLong() + count <= 32L * 1024 * 1024, "Encoded protobuf too large")
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(size + count, minOf(buffer.size * 2, 32 * 1024 * 1024)))
    }
    private fun varint(value: Long) {
        require(value >= 0)
        var rest = value
        while (rest >= 128) { append(((rest and 127) or 128).toByte()); rest = rest ushr 7 }
        append(rest.toByte())
    }
    fun number(field: Int, value: Long) {
        if (value == 0L) return // proto3 omits default scalars.
        varint(field.toLong() shl 3); varint(value)
    }
    fun bytes(field: Int, value: ByteArray) {
        varint((field.toLong() shl 3) or 2); varint(value.size.toLong()); reserve(value.size)
        value.copyInto(buffer, size); size += value.size
    }
    fun text(field: Int, value: String) { if (value.isNotEmpty()) bytes(field, value.encodeToByteArray()) }
    fun build(): ByteArray = buffer.copyOf(size)
}
internal fun proto(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).build()
internal fun Digest.wire(): ByteArray = proto { text(1, hash); number(2, sizeBytes) }

internal data class ProtoField(val number: Int, val wire: Int, val integer: Long = 0, val data: ByteArray = byteArrayOf())
internal class ProtoMessage(val data: ByteArray) {
    val fields: List<ProtoField>
    init {
        var position = 0
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                demand(position < data.size, "Truncated protobuf varint")
                val next = data[position++].toInt() and 255
                demand(index < 9 || next <= 1, "Oversized protobuf varint")
                value = value or ((next and 127).toLong() shl (index * 7))
                if (next and 128 == 0) return value
            }
            throw RemoteFailure("Oversized protobuf varint")
        }
        fun take(length: Long): ByteArray {
            demand(length >= 0 && length <= data.size - position, "Truncated protobuf field")
            return data.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
        }
        val result = mutableListOf<ProtoField>()
        while (position < data.size) {
            demand(result.size < 100_000, "Too many protobuf fields")
            val tag = varint()
            demand(tag >= 8 && tag ushr 3 <= 536_870_911, "Invalid protobuf tag")
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            result += when (wire) {
                0 -> ProtoField(field, wire, integer = varint())
                1 -> ProtoField(field, wire, data = take(8))
                2 -> ProtoField(field, wire, data = take(varint()))
                5 -> ProtoField(field, wire, data = take(4))
                else -> throw RemoteFailure("Unsupported protobuf wire type")
            }
        }
        fields = result
    }
    fun only(allowed: Set<Int>) { demand(fields.all { it.number in allowed }, "Unsupported protobuf field") }
    fun repeated(number: Int): List<ByteArray> = fields.filter { it.number == number }.map {
        demand(it.wire == 2, "Expected protobuf message"); it.data
    }
    fun bytes(number: Int, required: Boolean = false): ByteArray {
        val values = repeated(number)
        demand(values.size <= 1 && (!required || values.size == 1), "Duplicate or missing protobuf field $number")
        return values.firstOrNull() ?: byteArrayOf()
    }
    fun number(number: Int): Long {
        val values = fields.filter { it.number == number }
        demand(values.size <= 1 && values.all { it.wire == 0 }, "Invalid protobuf integer")
        return values.firstOrNull()?.integer ?: 0
    }
    fun text(number: Int, required: Boolean = false): String = bytes(number, required).decodeToString(throwOnInvalidSequence = true)
    fun digest(number: Int): Digest = ProtoMessage(bytes(number, true)).let {
        it.only(setOf(1, 2)); Digest(it.text(1, true), it.number(2))
    }
}
