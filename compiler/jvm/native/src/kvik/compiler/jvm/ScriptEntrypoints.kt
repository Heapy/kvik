package kvik.compiler.jvm

/**
 * Discover ordinary Kotlin/JVM main bridges from this script's compiled classes,
 * never from dependencies and never by loading or initializing application code.
 * Keys are paths relative to the compiler's class output directory.
 *
 * This reads class-file framing and method metadata, not bytecode verification.
 * Inherited mains and Java instance-main extensions are not inferred.
 */
fun selectScriptMainClass(classes: Map<String, ByteArray>, requested: String? = null): String {
    require(classes.size in 1..20_000) { "Script must produce 1..20000 class files" }
    require(classes.values.sumOf { it.size.toLong() } <= 256L * 1024 * 1024) { "Script classes exceed 256 MiB" }
    val candidates = classes.entries.sortedBy { it.key }.mapNotNull { (path, bytes) ->
        require(path.endsWith(".class") && !path.startsWith('/') && path.split('/').none { it in setOf("", ".", "..") }) {
            "Invalid script class output path: $path"
        }
        val metadata = ClassMetadata(bytes).read()
        require(path == "${metadata.internalName}.class") { "Class identity differs from output path: $path" }
        if (metadata.hasMain) metadata.internalName.replace('/', '.') else null
    }
    if (requested != null) {
        require(requested in candidates) { "Declared script mainClass '$requested' has no public static main(String[]) in script outputs" }
        return requested
    }
    require(candidates.size == 1) {
        if (candidates.isEmpty()) "Script has no public static main(String[]) entry point; declare a Kotlin main function"
        else "Script has multiple entry points: ${candidates.joinToString()}; declare mainClass explicitly"
    }
    return candidates.single()
}

private data class EntrypointMetadata(val internalName: String, val hasMain: Boolean)

/** JVMS 25, sections 4.1, 4.4, 4.5, 4.6 and 4.7. Attribute bodies stay opaque. */
private class ClassMetadata(private val bytes: ByteArray) {
    private var offset = 0
    private lateinit var tags: ByteArray
    private lateinit var starts: IntArray
    private lateinit var lengths: IntArray
    private lateinit var names: IntArray

    private fun invalid(message: String): Nothing = throw IllegalArgumentException("Invalid script class file: $message")
    private fun need(count: Int) {
        if (count < 0 || count > bytes.size - offset) invalid("truncated structure at byte $offset")
    }
    private fun u1(): Int { need(1); return bytes[offset++].toInt() and 255 }
    private fun u2(): Int = (u1() shl 8) or u1()
    private fun u4(): Long = (u2().toLong() shl 16) or u2().toLong()
    private fun skip(count: Long) {
        if (count !in 0L..(bytes.size - offset).toLong()) invalid("truncated structure at byte $offset")
        offset += count.toInt()
    }
    private fun pool(index: Int, tag: Int) {
        if (index !in 1 until tags.size || tags[index].toInt() != tag) invalid("invalid constant-pool reference $index")
    }
    private fun isText(index: Int, value: String): Boolean {
        pool(index, 1)
        return lengths[index] == value.length && value.indices.all { bytes[starts[index] + it].toInt() == value[it].code }
    }
    private fun text(index: Int): String {
        pool(index, 1)
        var position = starts[index]
        val end = position + lengths[index]
        fun next(): Int {
            if (position >= end) invalid("truncated modified UTF-8")
            return bytes[position++].toInt() and 255
        }
        fun continuation(): Int = next().also { if (it and 0xc0 != 0x80) invalid("invalid modified UTF-8 continuation") } and 0x3f
        return buildString {
            while (position < end) {
                val first = next()
                val value = when {
                    first in 1..0x7f -> first
                    first in 0xc0..0xdf -> ((first and 0x1f) shl 6 or continuation()).also {
                        if (it < 0x80 && !(first == 0xc0 && it == 0)) invalid("overlong modified UTF-8")
                    }
                    first in 0xe0..0xef -> ((first and 0x0f) shl 12 or (continuation() shl 6) or continuation()).also {
                        if (it < 0x800) invalid("overlong modified UTF-8")
                    }
                    else -> invalid("invalid modified UTF-8 leading byte")
                }
                append(value.toChar())
            }
        }
    }
    private fun className(index: Int): String {
        pool(index, 7)
        return text(names[index]).also { name ->
            if (name.isEmpty() || name.split('/').any { it.isEmpty() || it in setOf(".", "..") } ||
                name.any { it == '.' || it == ';' || it == '[' || it == '\\' || it.code < 32 || it.code == 127 }) {
                invalid("invalid internal class name")
            }
        }
    }
    private fun attributes() {
        repeat(u2()) { pool(u2(), 1); skip(u4()) }
    }

    fun read(): EntrypointMetadata {
        if (bytes.size > 64 * 1024 * 1024) invalid("class exceeds 64 MiB")
        if (u4() != 0xcafebabeL) invalid("wrong magic")
        val minor = u2()
        val major = u2()
        if (major !in 45..69 || major >= 56 && minor != 0) invalid("unsupported or preview class version $major.$minor")
        val count = u2()
        if (count < 1) invalid("empty constant-pool table")
        tags = ByteArray(count); starts = IntArray(count); lengths = IntArray(count); names = IntArray(count)
        var index = 1
        while (index < count) {
            val tag = u1(); tags[index] = tag.toByte()
            when (tag) {
                1 -> { val length = u2(); starts[index] = offset; lengths[index] = length; skip(length.toLong()) }
                3, 4, 9, 10, 11, 12, 17, 18 -> skip(4)
                5, 6 -> { skip(8); if (++index >= count) invalid("long/double has no reserved slot") }
                7 -> names[index] = u2()
                8, 16, 19, 20 -> skip(2)
                15 -> skip(3)
                else -> invalid("unknown constant-pool tag $tag")
            }
            index++
        }
        val flags = u2()
        val name = className(u2())
        val parent = u2()
        if (parent != 0) className(parent)
        repeat(u2()) { className(u2()) }
        repeat(u2()) { u2(); pool(u2(), 1); pool(u2(), 1); attributes() }
        var main = false
        repeat(u2()) {
            val access = u2(); val methodName = u2(); val descriptor = u2()
            pool(methodName, 1); pool(descriptor, 1)
            if (isText(methodName, "main") && isText(descriptor, "([Ljava/lang/String;)V") && access and 0x0009 == 0x0009 && access and 0x0400 == 0) main = true
            attributes()
        }
        attributes()
        if (offset != bytes.size) invalid("trailing bytes")
        // ACC_MODULE identifies module-info, never an executable script class.
        return EntrypointMetadata(name, main && flags and 0x8000 == 0)
    }
}
