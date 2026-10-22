package kvik.compiler.native

import kvik.core.*
import kotlin.test.*

/** Pure typed action protocol checks; no application or compiler is executed. */
class NativeApplicationActionsTest {
    private val digest = Sha256.digest("fixture".encodeToByteArray())
    private val tools = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
    private val library = LogicalPath("modules/app/main.klib")

    @Test fun explicitProgramEntrypointIsCanonicalAndKeyBound() {
        val compilation = NativeCompilation("app", emptyList(), output = LogicalPath("application.kexe"),
            produce = NativeOutput.PROGRAM, includedLibrary = library, entryPoint = "fixture.launch")
        val inputs = TreeManifest(listOf(FileEntry(library, digest)))
        val action = nativeCompileAction(compilation, inputs, digest, tools)
        assertEquals(compilation, NativeCompilation.fromJson(compilation.toJson()))
        assertEquals(listOf("-entry", "fixture.launch"), action.arguments.windowed(2).single { it.first() == "-entry" })
        assertTrue("-Xinclude=input/modules/app/main.klib" in action.arguments)
        assertTrue("-Xpartial-linkage-loglevel=error" in action.arguments)
        assertEquals(ActionKind.LINK, action.kind)
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertNotEquals(action.key(Sha256), nativeCompileAction(compilation.copy(entryPoint = "fixture.other"), inputs, digest, tools).key(Sha256))
        assertFails { nativeCompileAction(compilation, TreeManifest(emptyList()), digest, tools) }
    }

    @Test fun missingEntryPreservesLegacyParametersAndUnsupportedModesFail() {
        val legacy = NativeCompilation("app", listOf(LogicalPath("Main.kt")), output = LogicalPath("app.klib"))
        assertFalse("entryPoint" in legacy.toJson().objectValue())
        assertFalse("-entry" in legacy.arguments())
        assertEquals(legacy, NativeCompilation.fromJson(legacy.toJson()))
        assertFails { legacy.copy(entryPoint = "fixture.main") }
        val program = NativeCompilation("app", emptyList(), output = LogicalPath("app.kexe"), produce = NativeOutput.PROGRAM, includedLibrary = library)
        assertFalse("entryPoint" in program.toJson().objectValue())
        for (invalid in listOf("", "a..b", "x;command", "java.Main\$Nested", "a\n", "1.main", "a".repeat(1025))) {
            assertFails { program.copy(entryPoint = invalid) }
        }
        for (mode in listOf(NativeOutput.TEST, NativeOutput.KOTEST_TEST)) assertFails { program.copy(produce = mode, entryPoint = "fixture.main") }
        assertFails { NativeCompilation.fromJson(JsonValue.Object(program.toJson().objectValue() + ("entryPoint" to 1.json()))) }
    }
}
