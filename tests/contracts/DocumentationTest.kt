package kvik.core

import kotlin.test.*

class DocumentationTest {
    private val digest = Sha256.digest("jar".encodeToByteArray())
    private val distribution = DocumentationDistribution(DOKKA_VERSION, TreeManifest(listOf(
        "dokka-cli-2.2.0.jar", "dokka-base-2.2.0.jar", "analysis-kotlin-symbols-2.2.0.jar",
        "kotlinx-html-jvm-0.8.0.jar", "freemarker-2.3.31.jar").map { FileEntry(LogicalPath(it), digest) }))
    private val common = DocumentationSourceSet("common", "common", LogicalPath("sources/common"), emptyList(), emptyList(), setOf("jvm", "macosArm64"))
    private val jvm = DocumentationSourceSet("jvmMain", "jvm", LogicalPath("sources/jvm"), listOf(LogicalPath("dependencies/library.jar")), listOf("common"), setOf("jvm"))

    @Test fun distributionBindsExactlyTheStandaloneCliAndHtmlPlugins() {
        assertEquals(distribution.toJson(), DocumentationDistribution.fromJson(distribution.toJson()).toJson())
        assertEquals(Sha256.digest(distribution.toJson()), distribution.identity)
        assertEquals(4, distribution.pluginPaths.size)
        assertFalse(distribution.cliPath in distribution.pluginPaths)
        assertFails { distribution.copy(version = "2.1.0") }
        assertFails { distribution.copy(artifacts = TreeManifest(distribution.artifacts.files.drop(1))) }
        assertFails { distribution.copy(artifacts = TreeManifest(distribution.artifacts.files.map { it.copy(executable = true) })) }
    }

    @Test fun sourceSetEdgesAndTargetSelectionRoundTripWithoutImplicitInputs() {
        val value = DocumentationSpec("library", setOf("jvm", "macosArm64"), listOf(common, jvm),
            DocumentationSettings(includes = listOf(LogicalPath("Module.md")), reportUndocumented = true, failOnWarning = true))
        assertEquals(value, DocumentationSpec.fromJson(value.toJson()))
        assertEquals(DocumentationSettings(), DocumentationSettings.fromJson(obj()))
        assertFails { value.copy(sourceSets = listOf(jvm)) }
        assertFails { value.copy(sourceSets = listOf(common.copy(dependsOn = listOf("jvmMain")), jvm)) }
        assertFails { value.copy(sourceSets = listOf(common, jvm.copy(sourceRoot = LogicalPath("sources/common/jvm")))) }
        assertFails { value.copy(targets = setOf("js")) }
        assertFails { value.copy(sourceSets = listOf(common.copy(supportingTargets = setOf("macosArm64")), jvm)) }
        assertFails { jvm.copy(supportingTargets = emptySet()) }
        assertFails { DocumentationSettings.fromJson(obj("plugins" to emptyList<JsonValue>().json())) }
        assertFails { DocumentationSettings(documentedVisibilities = listOf("public", "public")) }
        assertFails { DocumentationSettings(includes = listOf(LogicalPath("Module.md"), LogicalPath("Module.md"))) }
    }

    @Test fun selectedTargetsPreserveFullSourceSetRefinementForMetadata() {
        val value = DocumentationSpec("library", setOf("jvm"), listOf(common, jvm))
        assertEquals(setOf("jvm", "macosArm64"), value.sourceSets.first().supportingTargets)
        assertEquals(value, DocumentationSpec.fromJson(value.toJson()))
        assertFails { value.copy(sourceSets = listOf(common.copy(supportingTargets = setOf("macosArm64")))) }
    }
}
