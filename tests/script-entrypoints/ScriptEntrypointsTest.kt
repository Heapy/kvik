package kvik.compiler.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptEntrypointsTest {
    @Test fun selectsTheMainBridgeAndIgnoresHelperClasses() {
        assertEquals("demo.MainKt", selectScriptMainClass(mapOf(
            "demo/Helper.class" to fixture("demo/Helper", access = 1),
            "demo/MainKt.class" to fixture("demo/MainKt"))))
    }
    @Test fun explicitSelectionDisambiguatesWithoutConsideringDependencies() {
        val outputs = mapOf("A.class" to fixture("A"), "B.class" to fixture("B"))
        assertFailsWith<IllegalArgumentException> { selectScriptMainClass(outputs) }
        assertEquals("B", selectScriptMainClass(outputs, "B"))
        assertFailsWith<IllegalArgumentException> { selectScriptMainClass(outputs, "dependency.MainKt") }
    }
    @Test fun rejectsInstancePrivateAbstractAndWrongSignatureMethods() {
        for (access in listOf(1, 8, 10, 0x409)) {
            assertFailsWith<IllegalArgumentException> { selectScriptMainClass(mapOf("A.class" to fixture("A", access = access))) }
        }
        for (signature in listOf("()V", "([Ljava/lang/String;)I", "(Ljava/lang/String;)V")) {
            assertFailsWith<IllegalArgumentException> { selectScriptMainClass(mapOf("A.class" to fixture("A", descriptor = signature))) }
        }
    }
    @Test fun refusesMissingOrMismatchedClassIdentity() {
        assertFailsWith<IllegalArgumentException> { selectScriptMainClass(emptyMap()) }
        for (path in listOf("B.class", "../A.class", "/A.class", "A.kt")) {
            assertFailsWith<IllegalArgumentException> { selectScriptMainClass(mapOf(path to fixture("A"))) }
        }
    }
    @Test fun handlesWideConstantsAndModifiedUtf8Names() {
        val name = "demo/\u041f\u0440\u0438\u043c\u0435\u0440\ud801\udc00"
        assertEquals(name.replace('/', '.'), selectScriptMainClass(mapOf("$name.class" to fixture(name, wideConstants = true))))
    }
    @Test fun rejectsEveryTruncationAndTrailingBytes() {
        val bytes = fixture("A", wideConstants = true)
        for (length in bytes.indices) {
            assertFailsWith<IllegalArgumentException>("accepted prefix $length") {
                selectScriptMainClass(mapOf("A.class" to bytes.copyOf(length)))
            }
        }
        assertFailsWith<IllegalArgumentException> { selectScriptMainClass(mapOf("A.class" to (bytes + 0))) }
    }
    @Test fun rejectsCorruptPoolMagicVersionAndAttributeLengths() {
        val bytes = fixture("A")
        for ((offset, replacement) in listOf(0 to 0, 7 to 70, 5 to 1, 10 to 99, 13 to 0)) {
            val broken = bytes.copyOf(); broken[offset] = replacement.toByte()
            // Minor versions below major56 are permitted, so force major69 in this fixture.
            assertFailsWith<IllegalArgumentException>("accepted byte $offset") { selectScriptMainClass(mapOf("A.class" to broken)) }
        }
        val broken = bytes.copyOf()
        // The final attribute's declared length is the last four bytes of this fixture.
        for (i in broken.size - 4 until broken.size) broken[i] = 0xff.toByte()
        assertFailsWith<IllegalArgumentException> { selectScriptMainClass(mapOf("A.class" to broken)) }
    }
}

/** Minimal framed metadata, deliberately not executable bytecode; real compiler fixtures are separate. */
private fun fixture(name: String, access: Int = 9, descriptor: String = "([Ljava/lang/String;)V", wideConstants: Boolean = false): ByteArray {
    val result = mutableListOf<Byte>()
    fun u1(value: Int) { result += value.toByte() }
    fun u2(value: Int) { u1(value ushr 8); u1(value) }
    fun u4(value: Long) { u2((value ushr 16).toInt()); u2(value.toInt()) }
    fun utf(value: String) {
        val data = mutableListOf<Int>()
        for (c in value) when (val n = c.code) {
            in 1..0x7f -> data += n
            in 0..0x7ff -> { data += 0xc0 or (n shr 6); data += 0x80 or (n and 63) }
            else -> { data += 0xe0 or (n shr 12); data += 0x80 or ((n shr 6) and 63); data += 0x80 or (n and 63) }
        }
        u1(1); u2(data.size); data.forEach(::u1)
    }
    u4(0xcafebabeL); u2(0); u2(69); u2(if (wideConstants) 12 else 8)
    utf(name) // 1
    u1(7); u2(1) // 2
    utf("java/lang/Object") // 3
    u1(7); u2(3) // 4
    utf("main") // 5
    utf(descriptor) // 6
    utf("UnknownAttribute") // 7
    if (wideConstants) { u1(5); u4(1); u4(2); u1(6); u4(3); u4(4) }
    u2(0x21); u2(2); u2(4); u2(0); u2(0)
    u2(1); u2(access); u2(5); u2(6); u2(0)
    u2(1); u2(7); u4(0)
    return result.toByteArray()
}
