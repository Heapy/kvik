package kvik.core

import kotlin.test.*

class LibraryPublicationTest {
    private val base = Coordinate("org.example", "library", "1.0.0")
    private val target = TargetSpec("jvm", "main", null, emptyList())
    private fun module(id: String = "library", targets: List<TargetSpec> = listOf(target), publication: LibraryPublicationSpec? = null) =
        ModuleSpec(id, listOf(SourceSet("main", listOf(LogicalPath("src")), emptyList(), emptyList(), emptyList())), targets, emptyList(), publication)
    private fun document(publication: String? = null, id: String = "library", targetName: String = "jvm"): String = """
        {"id":"$id","sourceSets":[{"name":"main","directories":["src"],"resources":[],"dependsOn":[],"dependencies":[]}],
        "targets":[{"name":"$targetName","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":[]${publication?.let { ",\"publication\":$it" } ?: ""}}
    """.trimIndent()
    private fun parse(vararg modules: String) = parseProjectConfiguration(parseJson("""
        {"schemaVersion":1,"name":"sample","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[${modules.joinToString(",")}]}
    """))

    @Test fun targetCoordinatesAreExplicitAndOldDocumentsRemainUnchanged() {
        val spec = LibraryPublicationSpec(base)
        assertEquals(Coordinate("org.example", "library-jvm", "1.0.0"), spec.coordinateFor("jvm"))
        assertEquals(Coordinate("org.example", "library-macosarm64", "1.0.0"), spec.coordinateFor("macosArm64"))
        assertEquals(Coordinate("org.example", "library-linuxx64", "1.0.0"), spec.coordinateFor("linuxX64"))
        for (target in listOf("common", "js", "macosarm64", "")) assertFails { spec.coordinateFor(target) }
        val legacy = parse(document())
        assertNull(legacy.project.modules.single().publication)
        assertEquals(module(), legacy.project.modules.single())
        assertEquals(legacy.digest, Sha256.digest(legacy.document))
        val configured = parse(document("""{"coordinate":"$base"}"""))
        assertEquals(spec, configured.project.modules.single().publication)
        assertEquals(configured, parseProjectConfiguration(parseJson(configured.document.canonical())))
        assertNotEquals(legacy.digest, configured.digest)
        assertNotEquals(configured.digest, parse(document("""{"coordinate":"org.example:library:2.0.0"}""")).digest)
    }

    @Test fun declarationsRejectMalformedAndUnrequestedRemoteSettings() {
        for (value in listOf("null", "[]", "{}", """{"coordinate":null}""", """{"coordinate":1}""",
            """{"coordinate":"$base","repository":"central"}""", """{"coordinate":"$base","credentials":"token"}"""))
            assertFails(value) { parse(document(value)) }
        for (coordinate in listOf("g:a", "g:a:1:classifier", "g:a:LATEST", "g:a:RELEASE", "g:a:1-SNAPSHOT", "g:..:1", "g:a:../1", "g:a:1\n"))
            assertFails(coordinate) { parse(document(obj("coordinate" to coordinate.json()).canonical())) }
    }

    @Test fun duplicateDerivedCoordinatesAreRejectedAcrossModules() {
        val declaration = """{"coordinate":"$base"}"""
        assertFails { parse(document(declaration), document(declaration, id = "other")) }
        assertFails { ProjectSpec("sample", emptyList(), listOf(module(publication = LibraryPublicationSpec(base)), module("other", publication = LibraryPublicationSpec(base)))) }
        // Distinct target coordinates are allowed even when their base coordinates agree.
        parse(document(declaration), document(declaration, "other", "macosArm64"))
        parse(document(declaration), document("""{"coordinate":"org.example:library:2.0.0"}""", "other"))
        val root = module(targets = listOf(target, target.copy(name = "macosArm64")), publication = LibraryPublicationSpec(base))
        val collision = module("other", publication = LibraryPublicationSpec(Coordinate("org.example", "library-jvm", "1.0.0")),
            targets = listOf(target, target.copy(name = "macosArm64")))
        assertFails { ProjectSpec("sample", emptyList(), listOf(root, collision)) }
    }

    @Test fun applicationsCannotBePublishedAndPluginDeclarationsRemainAvailable() {
        val declaration = """{"coordinate":"$base"}"""
        assertFails { parse(document(declaration).replace("\"compilerOptions\":[]", "\"compilerOptions\":[],\"application\":{\"entryPoint\":\"sample.MainKt\"}")) }
        assertFails { module(targets = listOf(target.copy(application = ApplicationSpec("sample.MainKt"))), publication = LibraryPublicationSpec(base)) }
        assertFails { module(targets = listOf(target.copy(name = "js")), publication = LibraryPublicationSpec(base)) }
        val plugin = """{"coordinate":"org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20","targets":["jvm"],"scopes":["main"],"options":[],"inputs":[]}"""
        val parsed = parse(document(declaration).replace("\"compilerPlugins\":[]", "\"compilerPlugins\":[$plugin]"))
        assertEquals("kotlin-serialization-compiler-plugin-embeddable", parsed.project.modules.single().compilerPlugins.single().coordinate?.name)
    }
}
