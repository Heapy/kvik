package kvik.core

import kotlin.test.*

class ProjectInspectionTest {
    @Test fun effectiveDocumentMustMatchItsDigestAndAuthoringAndSettingsRetainOrigins() {
        val document = obj("schemaVersion" to 1.json(), "project" to project.document, "resolver" to obj("maxComponents" to 256.json()))
        val setting = InspectedSetting(listOf("-jvm-target=17".json()).json(), "inherited",
            listOf(ConfigurationOrigin(LogicalPath("kvik.json"), "/modules/0/targets/0/compilerOptions")))
        val value = inspection.copy(configuration = Sha256.digest(document), effectiveConfiguration = document, settings = mapOf("testOptions" to setting))
        assertEquals(value, ProjectInspection.fromJson(parseJson(value.toJson().canonical())))
        assertFails { value.copy(configuration = digest) }
        val other = JsonValue.Object(document.values + ("project" to obj()))
        assertFails { value.copy(configuration = Sha256.digest(other), effectiveConfiguration = other) }
        assertFails { InspectedSetting(setting.value, "configuration") }
        assertFails { InspectedSetting(setting.value, "guessed") }
        val malformed = value.toJson().values + ("effectiveConfiguration" to JsonValue.Null)
        assertFails { ProjectInspection.fromJson(JsonValue.Object(malformed)) }
    }

    private val digest = Digest("a".repeat(64), 42)
    private val project = parseProjectConfiguration(parseJson("""{
        "schemaVersion":1,"name":"sample","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
        "modules":[],"fileTags":{"notes.txt":["manual:review"]}
    }"""))
    private val inspection = ProjectInspection(project, digest, digest,
        listOf(FileInspection(LogicalPath("notes.txt"), listOf("z", "a", "z"), listOf("shared"),
            listOf("future-target", "jvm"), listOf("manual:review", "kind:unclassified"))),
        mapOf("module:sample" to ConfigurationOrigin(LogicalPath("kvik.json"), "/modules/0")),
        listOf("project-inspection", "future-capability"))

    @Test fun localInspectionRoundTripsWithoutNormalizingTheAuthoredDocumentOrArrays() {
        val json = inspection.toJson()
        val decoded = ProjectInspection.fromJson(parseJson(json.canonical()))
        assertEquals(inspection, decoded)
        assertEquals(json, decoded.toJson())
        assertEquals(project.document, decoded.project.document)
        assertEquals(listOf("z", "a", "z"), decoded.files.single().modules)
        assertNull(decoded.source)
        assertFalse("source" in json.values)
    }

    @Test fun legacyMissingTagsAndProviderContextRoundTrip() {
        val legacy = inspection.copy(files = listOf(inspection.files.single().copy(tags = null)))
        assertFalse("tags" in legacy.toJson().values.getValue("files").arrayValue().single().objectValue())
        assertEquals(legacy, ProjectInspection.fromJson(legacy.toJson()))
        for (root in listOf("", "nested/project")) {
            val source = SourceReference(SourceProvenance("kode", "sample", "revision", "tree"), root, digest)
            val provider = legacy.copy(source = source)
            val json = provider.toJson()
            assertEquals(source.provenance.toJson(), json.values.getValue("source"))
            assertEquals(root, json.values.getValue("sourceRoot").stringValue())
            assertEquals(digest.toJson(), json.values.getValue("sourceManifest"))
            assertEquals(provider, ProjectInspection.fromJson(parseJson(json.canonical())))
            assertEquals(json, ProjectInspection.fromJson(json).toJson())
        }
    }

    @Test fun strictTopLevelAndNestedContractsRejectMalformedInspections() {
        val valid = inspection.toJson().values
        for (required in valid.keys) {
            assertFails("accepted missing $required") { ProjectInspection.fromJson(JsonValue.Object(valid - required)) }
            assertFails("accepted null $required") { ProjectInspection.fromJson(JsonValue.Object(valid + (required to JsonValue.Null))) }
        }
        for ((field, value) in listOf("extra" to true.json(), "schemaVersion" to 2.json(),
            "configuration" to obj("hash" to "bad".json(), "sizeBytes" to 42.json()),
            "composition" to obj("hash" to digest.hash.json(), "sizeBytes" to (-1).json()),
            "capabilities" to listOf(1.json()).json(), "project" to obj())) {
            assertFails("accepted $field=$value") { ProjectInspection.fromJson(JsonValue.Object(valid + (field to value))) }
        }
        val origin = inspection.origins.values.single().toJson().values
        for (invalid in listOf(origin - "pointer", origin + ("extra" to true.json()),
            origin + ("file" to "../kvik.json".json()), origin + ("pointer" to 1.json()))) {
            assertFails { ProjectInspection.fromJson(JsonValue.Object(valid + ("origins" to obj("sample" to JsonValue.Object(invalid))))) }
        }
    }

    @Test fun fileInspectionRejectsInvalidPathsOwnershipAndTags() {
        val valid = inspection.files.single().toJson().values
        for (required in listOf("path", "modules", "sourceSets", "targets")) {
            assertFails { FileInspection.fromJson(JsonValue.Object(valid - required)) }
        }
        for ((field, value) in listOf("extra" to true.json(), "path" to "/absolute.kt".json(),
            "modules" to listOf("bad/id".json()).json(), "sourceSets" to listOf("".json()).json(),
            "targets" to listOf(1.json()).json(), "tags" to JsonValue.Null,
            "tags" to emptyList<JsonValue>().json(), "tags" to listOf("unqualified".json()).json(),
            "tags" to listOf("manual:review".json(), "manual:review".json()).json())) {
            assertFails("accepted $field=$value") { FileInspection.fromJson(JsonValue.Object(valid + (field to value))) }
        }
        assertFails { FileInspection(LogicalPath("file.kt"), emptyList(), emptyList(), emptyList(), emptyList()) }
        assertEquals("", ConfigurationOrigin.fromJson(obj("file" to "kvik.json".json(), "pointer" to "".json())).pointer)
    }

    @Test fun providerContextMustBeCompleteAndValid() {
        val valid = inspection.copy(source = SourceReference(SourceProvenance("kode", "sample", "revision", "tree"), "", digest)).toJson().values
        val sourceFields = listOf("source", "sourceRoot", "sourceManifest")
        for (mask in 1..6) {
            val partial = valid - sourceFields.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            assertFails { ProjectInspection.fromJson(JsonValue.Object(partial)) }
        }
        for (field in sourceFields) {
            assertFails { ProjectInspection.fromJson(JsonValue.Object(valid + (field to JsonValue.Null))) }
        }
        assertFails { ProjectInspection.fromJson(JsonValue.Object(valid + ("sourceRoot" to "../escape".json()))) }
        assertFails { ProjectInspection.fromJson(JsonValue.Object(valid + ("source" to obj()))) }
    }
}
