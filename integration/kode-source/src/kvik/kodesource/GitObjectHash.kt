package kvik.kodesource

/** Git object IDs stay provider-local; portable inputs always use Kvik SHA-256 of content alone. */
internal fun gitObjectId(kind: String, bytes: ByteArray, width: Int, checkpoint: () -> Unit): String {
    require(kind == "blob" || kind == "tree" || kind == "commit")
    require(width == 40 || width == 64)
    val header = "$kind ${bytes.size}\u0000".encodeToByteArray()
    val length = header.size.toLong() + bytes.size
    val blocks = (length + 9 + 63) / 64
    val total = blocks * 64
    val bits = length * 8
    fun byteAt(offset: Long): Int = when {
        offset < header.size -> header[offset.toInt()].toInt() and 255
        offset < length -> bytes[(offset - header.size).toInt()].toInt() and 255
        offset == length -> 128
        offset >= total - 8 -> ((bits ushr ((total - offset - 1) * 8).toInt()) and 255).toInt()
        else -> 0
    }
    checkpoint()
    val result = if (width == 40) GitHashes.sha1(blocks, ::byteAt, checkpoint) else GitHashes.sha256(blocks, ::byteAt, checkpoint)
    checkpoint()
    return result.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}

private object GitHashes {
    private val constants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun sha1(blocks: Long, byteAt: (Long) -> Int, checkpoint: () -> Unit): IntArray {
        val state = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476, 0xc3d2e1f0.toInt())
        val words = IntArray(80)
        for (block in 0 until blocks) {
            if (block % 4096 == 0L) checkpoint()
            for (i in 0..15) {
                val offset = block * 64 + i * 4
                words[i] = (byteAt(offset) shl 24) or (byteAt(offset + 1) shl 16) or (byteAt(offset + 2) shl 8) or byteAt(offset + 3)
            }
            for (i in 16..79) words[i] = (words[i - 3] xor words[i - 8] xor words[i - 14] xor words[i - 16]).rotateLeft(1)
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]; var e = state[4]
            for (i in 0..79) {
                val f = when (i) {
                    in 0..19 -> (b and c) or (b.inv() and d)
                    in 20..39 -> b xor c xor d
                    in 40..59 -> (b and c) or (b and d) or (c and d)
                    else -> b xor c xor d
                }
                val k = when (i) { in 0..19 -> 0x5a827999; in 20..39 -> 0x6ed9eba1; in 40..59 -> 0x8f1bbcdc.toInt(); else -> 0xca62c1d6.toInt() }
                val next = a.rotateLeft(5) + f + e + k + words[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = next
            }
            state[0] += a; state[1] += b; state[2] += c; state[3] += d; state[4] += e
        }
        return state
    }

    fun sha256(blocks: Long, byteAt: (Long) -> Int, checkpoint: () -> Unit): IntArray {
        val state = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
        val words = IntArray(64)
        for (block in 0 until blocks) {
            if (block % 4096 == 0L) checkpoint()
            for (i in 0..15) {
                val offset = block * 64 + i * 4
                words[i] = (byteAt(offset) shl 24) or (byteAt(offset + 1) shl 16) or (byteAt(offset + 2) shl 8) or byteAt(offset + 3)
            }
            for (i in 16..63) {
                val x = words[i - 15]; val y = words[i - 2]
                words[i] = words[i - 16] + (x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)) + words[i - 7] + (y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10))
            }
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
            for (i in 0..63) {
                val t1 = h + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + constants[i] + words[i]
                val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
                h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            val result = intArrayOf(a, b, c, d, e, f, g, h)
            for (i in state.indices) state[i] += result[i]
        }
        return state
    }
}
