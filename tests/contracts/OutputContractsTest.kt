package kvik.core.tests

import kvik.core.*
import kotlin.test.*

class OutputContractsTest {
    private val digest = Sha256.digest(byteArrayOf())
    private fun action(vararg paths: String) = ActionSpec("fixture", digest, ActionKind.COMPILE, mapOf("os" to "test"), "jvm",
        mapOf("compiler" to digest), TreeManifest(emptyList()), paths.map(::LogicalPath), emptyList(), emptyMap())
    private fun files(vararg paths: String) = TreeManifest(paths.map { FileEntry(LogicalPath(it), digest) })

    @Test fun declaredRootsAndShapesAreDistinctFromGeneratedFileInventory() {
        val contract = ActionOutputContract(fileOutput("library.klib", "klib"), treeOutput("classes", "jvm-classes"),
            OutputArtifact(LogicalPath("plugins/report"), "compiler-plugin-output", ArtifactShape.FILE_OR_TREE))
        contract.requireMatches(action("classes", "plugins/report", "library.klib"))
        contract.requireMatches(files("library.klib", "classes/pkg/Main.class", "plugins/report"))
        contract.requireMatches(files("plugins/report/data.json"))
        contract.requireMatches(files()) // Declaration does not promise that optional/generated roots contain files.
        for (invalid in listOf(files("classes"), files("library.klib/file"), files("classes-other/Main.class")))
            assertFailsWith<IllegalArgumentException> { contract.requireMatches(invalid) }
        assertFailsWith<IllegalArgumentException> { contract.requireMatches(action("classes")) }
    }

    @Test fun nodeCopyAndLegacyConstructorsPreserveSourceCompatibilityAndMetadata() {
        val mutable = mutableListOf(treeOutput("classes", "jvm-classes"))
        val contract = ActionOutputContract(mutable)
        mutable.clear()
        val node = ActionNode("compile", emptySet(), contract) { action("classes") }
        val copied = node.copy(id = "renamed", dependencies = setOf("producer"))
        assertSame(contract, copied.outputContract)
        assertEquals(listOf(LogicalPath("classes")), copied.resolve(emptyMap()).outputs)
        val legacy = ActionNode("legacy", emptySet()) { action("classes") }
        assertNull(legacy.outputContract)
        val (id, dependencies, resolve) = legacy
        assertEquals("legacy", id); assertTrue(dependencies.isEmpty()); assertEquals(legacy.resolve(emptyMap()), resolve(emptyMap()))
        assertFailsWith<IllegalArgumentException> { ActionNode("bad", emptySet(), contract) { action("other") }.resolve(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ActionOutputContract(treeOutput("classes", "classes"), fileOutput("classes", "classes")) }
    }
}
