package kvik.compiler.native

import kvik.core.*
import kotlin.test.*

class NativeFragmentTest {
    private val common = LogicalPath("Common.kt")
    private val native = LogicalPath("Native.kt")
    private fun compilation(target: NativeTarget = NativeTarget.IOS_ARM64) = NativeCompilation("fixture", listOf(common, native),
        commonSources = listOf(common, native), output = LogicalPath("fixture.klib"), target = target,
        sourceFragments = listOf(NativeSourceFragment("commonMain", listOf(common)),
            NativeSourceFragment("sharedEmpty", emptyList(), listOf("commonMain")),
            NativeSourceFragment("nativeMain", listOf(native), listOf("sharedEmpty")),
            NativeSourceFragment("iosMain", emptyList(), listOf("nativeMain"))))

    @Test fun hierarchyArgumentsRoundTripAndActualCompilerPlans() {
        for (target in listOf(NativeTarget.IOS_ARM64, NativeTarget.IOS_SIMULATOR_ARM64)) {
            val value = compilation(target)
            assertEquals(value, NativeCompilation.fromJson(value.toJson()))
            assertContains(value.arguments(), "-Xfragments=iosMain")
            assertContains(value.arguments(), "-Xfragment-refines=nativeMain:sharedEmpty")
            assertContains(value.arguments(), "-Xfragment-sources=nativeMain:input/Native.kt")
            assertTrue(value.arguments().none { it.startsWith("-Xcommon-sources=") })
            println("KVIK_NATIVE_FRAGMENT_PLAN=" + obj("target" to target.projectName.json(), "arguments" to value.arguments().map { it.json() }.json()).canonical())
        }
    }
    @Test fun independentTestSetKeepsMainInternalVisibility() {
        val main = LogicalPath("Main.kt")
        val test = LogicalPath("Test.kt")
        val value = NativeCompilation("fixture", listOf(main, test), output = LogicalPath("fixture.klib"),
            sourceFragments = listOf(NativeSourceFragment("main", listOf(main)), NativeSourceFragment("commonTest", listOf(test), listOf("main")),
                NativeSourceFragment("nativeTest", emptyList(), listOf("commonTest")),
                NativeSourceFragment("terminal", emptyList(), listOf("nativeTest"))))
        println("KVIK_NATIVE_SIBLING_PLAN=" + value.arguments().map { it.json() }.json().canonical())
    }
    @Test fun legacyAndSourceFreeLinkRemainUnchanged() {
        val old = compilation().copy(sourceFragments = emptyList())
        assertFalse("sourceFragments" in old.toJson().values)
        assertContains(old.arguments(), "-Xcommon-sources=input/Common.kt,input/Native.kt")
        val link = NativeCompilation("fixture", emptyList(), output = LogicalPath("Fixture.framework"),
            produce = NativeOutput.FRAMEWORK, includedLibrary = LogicalPath("fixture.klib"), target = NativeTarget.IOS_ARM64)
        assertTrue(link.sourceFragments.isEmpty())
        assertTrue(link.arguments().none { it.startsWith("-Xfragment") || it == "-Xmulti-platform" })
        assertFailsWith<IllegalArgumentException> { link.copy(sourceFragments = compilation().sourceFragments) }
    }
    @Test fun ownershipAndRefinementAreValidated() {
        val original = compilation()
        assertFailsWith<IllegalArgumentException> { original.copy(sourceFragments = original.sourceFragments.dropLast(2)) }
        assertFailsWith<IllegalArgumentException> { original.copy(sourceFragments = original.sourceFragments.map { if (it.name == "nativeMain") it.copy(sources = listOf(common, native)) else it }) }
        assertFailsWith<IllegalArgumentException> { original.copy(sourceFragments = original.sourceFragments.map { if (it.name == "sharedEmpty") it.copy(dependsOn = listOf("iosMain")) else it }) }
        assertFailsWith<IllegalArgumentException> { original.copy(sourceFragments = original.sourceFragments.map { if (it.name == "sharedEmpty") it.copy(dependsOn = listOf("missing")) else it }) }
        assertFailsWith<IllegalArgumentException> { NativeSourceFragment("bad:name", emptyList()) }
    }
    @Test fun generatedKotestSourcesBelongToTerminalAndUsePreparedPaths() {
        val launcher = LogicalPath("generated/launcher.kt")
        val value = compilation(NativeTarget.MACOS_ARM64).let { it.copy(sources = it.sources + launcher, kotestLauncher = launcher,
            sourceFragments = it.sourceFragments.dropLast(1) + it.sourceFragments.last().copy(sources = listOf(launcher))) }
        assertContains(value.arguments(), "-Xfragment-sources=iosMain:prepared/kotest.kt")
        assertContains(value.arguments(), "-Xfragment-sources=iosMain:prepared/KvikKotestRuntime.kt")
        assertTrue(value.arguments().none { "input/generated/launcher.kt" in it })
        assertFailsWith<IllegalArgumentException> { value.copy(sourceFragments = value.sourceFragments.map { when(it.name) {
            "commonMain" -> it.copy(sources = it.sources + launcher)
            "iosMain" -> it.copy(sources = emptyList())
            else -> it
        } }) }
    }
}
