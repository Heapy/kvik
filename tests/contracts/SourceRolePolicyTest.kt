package kvik.core

import kotlin.test.*

class SourceRolePolicyTest {
    private val reference = SourceReference(SourceProvenance("kode", "library", "revision-1", "tree-1"), "sub",
        Sha256.digest("inventory".encodeToByteArray()))
    private val rules = ProjectFileTagRules(
        listOf(TaggedProjectFile(LogicalPath("kvik.json"), setOf("kind:configuration"))),
        listOf(TaggedProjectFile(LogicalPath("src"), setOf("scope:production", "kind:source")),
            TaggedProjectFile(LogicalPath("test"), setOf("scope:test", "kind:source"))),
        listOf(TaggedProjectFile(LogicalPath("docs/design.md"), setOf("manual:architecture"))),
    )
    private val files = listOf("kvik.json", "test/Test.kt", "src/Main.kt", "docs/design.md").map {
        TaggedProjectFile(LogicalPath("sub/$it"), rules.tags(LogicalPath(it)))
    }
    private fun policy(editable: FileTagPredicate? = null) = SourceRolePolicy("test-author",
        SourceView("tests", reference, roleFilePredicate("tests"), files.filter { roleFilePredicate("tests").matches(it.tags) }),
        rules, editable)

    @Test fun rulesRoundTripAndClassifyExactPathsAncestorsAndManualOnlyFiles() {
        val restored = ProjectFileTagRules.fromJson(parseJson(rules.toJson().canonical()))
        assertEquals(rules.toJson(), restored.toJson())
        for (path in listOf("test/New.kt", "test/deep/New.kt", "test", "testing/New.kt", "docs/design.md", "unknown"))
            assertEquals(rules.tags(LogicalPath(path)), restored.tags(LogicalPath(path)))
        assertEquals(setOf("scope:test", "kind:source"), restored.tags(LogicalPath("test/New.kt")))
        assertEquals(setOf("kind:unclassified", "manual:architecture"), restored.tags(LogicalPath("docs/design.md")))
        assertEquals(setOf("kind:unclassified"), restored.tags(LogicalPath("testing/New.kt")))
        assertEquals(setOf("kind:unclassified"), restored.tags(LogicalPath("test")))
    }

    @Test fun defaultDeniesEditsAndCapabilitiesWhileSelectedTagsAllowNewFiles() {
        val readonly = policy()
        assertTrue(readonly.capabilities.isEmpty())
        assertFalse(readonly.canEdit(LogicalPath("sub/test/Test.kt")))
        val writable = policy(FileTagPredicate(all = setOf("scope:test")))
        assertTrue(writable.canEdit(LogicalPath("sub/test/Test.kt")))
        assertTrue(writable.canEdit(LogicalPath("sub/test/New.kt")))
        assertFalse(writable.canEdit(LogicalPath("sub/src/Main.kt")))
        assertFalse(writable.canEdit(LogicalPath("sub/kvik.json")))
        assertFalse(writable.canEdit(LogicalPath("submarine/test/New.kt")))
        assertFalse(writable.canEdit(LogicalPath("test/New.kt")))
        assertEquals(writable, SourceRolePolicy.fromJson(parseJson(writable.toJson().canonical())))
    }

    @Test fun editsAreIndependentOfReadVisibilityButStayWithinRoot() {
        val hidden = policy(FileTagPredicate(all = setOf("manual:architecture")))
        assertFalse(hidden.view.files.any { it.path.value == "sub/docs/design.md" })
        assertTrue(hidden.canEdit(LogicalPath("sub/docs/design.md")))
        val all = policy(FileTagPredicate(none = setOf("kind:configuration")))
        assertTrue(all.canEdit(LogicalPath("sub/unknown")))
        assertFalse(all.canEdit(LogicalPath("sub/kvik.json")))
        assertFalse(all.canEdit(LogicalPath("outside")))
        assertFalse(all.canEdit(LogicalPath("sub")))
        val rootless = all.copy(view = all.view.copy(source = reference.copy(root = ""), files = all.view.files.map {
            it.copy(path = LogicalPath(it.path.value.removePrefix("sub/")))
        }))
        assertTrue(rootless.canEdit(LogicalPath("new.kt")))
    }

    @Test fun rulesAndPoliciesRejectDuplicatesUnknownCapabilitiesAndInconsistentViews() {
        assertFails { rules.copy(exact = rules.exact + rules.exact) }
        assertFails { rules.copy(manual = listOf(TaggedProjectFile(LogicalPath("file"), setOf("scope:test")))) }
        assertFails { rules.copy(manual = listOf(TaggedProjectFile(LogicalPath("file"), setOf("manual:" + "x".repeat(122))))) }
        assertFails { rules.copy(exact = List(100001) { rules.exact.first() }) }
        val valid = policy(FileTagPredicate())
        assertFails { valid.copy(name = "bad name") }
        assertFails { valid.copy(name = "a".repeat(129)) }
        assertFails { valid.copy(capabilities = setOf("admin")) }
        val changed = valid.view.files.map { it.copy(tags = it.tags + "manual:forged") }
        assertFails { valid.copy(view = valid.view.copy(files = changed)) }
        for ((key, value) in listOf("schemaVersion" to 2.json(), "kind" to "source-view".json(), "extra" to true.json(),
            "capabilities" to listOf("task-create".json(), "task-create".json()).json()))
            assertFails { SourceRolePolicy.fromJson(JsonValue.Object(valid.toJson().values + (key to value))) }
        val permitted = valid.copy(capabilities = SOURCE_ROLE_CAPABILITIES)
        assertEquals(permitted, SourceRolePolicy.fromJson(permitted.toJson()))
    }
}
