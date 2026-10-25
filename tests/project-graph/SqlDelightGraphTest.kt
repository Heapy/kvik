package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.js.*
import kvik.compiler.native.*
import kvik.compiler.sqldelight.*
import kotlin.test.*

class SqlDelightGraphTest {
    private fun digest(value: String) = Sha256.digest(value.encodeToByteArray())
    private val tools = listOf("jvm", "js", "wasmJs").associateWith { listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::digest) } +
        mapOf("macosArm64" to listOf("native", "jdk", "sdk", "native-dependencies").associateWith(::digest))
    private val settings = SqlDelightSpec("main", LogicalPath("data/sql"), "fixture.database", "Database")
    private fun configuration(spec: SqlDelightSpec = settings, documentation: Boolean = false): ProjectConfiguration {
        fun module(id: String, sql: SqlDelightSpec?) = obj("id" to id.json(),
            "sourceSets" to listOf(obj("name" to "main".json(), "directories" to listOf("$id/src".json()).json(),
                "resources" to emptyList<JsonValue>().json(), "dependsOn" to emptyList<JsonValue>().json(),
                "dependencies" to listOf(obj("coordinate" to "g:runtime:1".json())).json())).json(),
            "targets" to listOf(obj("name" to "jvm".json(), "main" to "main".json(), "test" to JsonValue.Null,
                "compilerOptions" to emptyList<JsonValue>().json())).json(), "compilerPlugins" to emptyList<JsonValue>().json())
            .let { if (sql == null) it else JsonValue.Object(it.values + ("sqlDelight" to sql.toJson())) }
            .let { if (!documentation || id != "data") it else JsonValue.Object(it.values + ("documentation" to DocumentationSettings().toJson())) }
        return parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "sqlFixture".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
            "modules" to listOf(module("data", spec), module("other", spec.copy(sourceDirectory = LogicalPath("other/sql"))), module("plain", null)).json()))
    }
    private fun files(sql: String = "schema", includeSql: Boolean = true, includeKotlin: Boolean = true) = TreeManifest(buildList {
        for (module in listOf("data", "other", "plain")) {
            if (includeKotlin || module != "data") add(FileEntry(LogicalPath("$module/src/Main.kt"), digest("$module Kotlin")))
            if (module != "plain" && (includeSql || module != "data")) add(FileEntry(LogicalPath("$module/sql/fixture/Tables.sq"), digest(if (module == "data") sql else "other SQL")))
        }
    })
    private fun graph(configuration: ProjectConfiguration = configuration(), files: TreeManifest = files(), changedTool: Boolean = false, mode: ProjectMode = ProjectMode.BUILD, targets: Set<String> = setOf("jvm"), modules: Set<String> = if (mode == ProjectMode.DOCUMENTATION) setOf("data") else setOf("data", "other", "plain")): ProjectActionGraph {
        val effective = effectiveConfiguration(configuration)
        val lock = DependencyLock(effective.digest, effective.requests.map { request ->
            val artifact = LockedArtifact(request.coordinate, request.variant.usage, "central",
                "https://repo.maven.apache.org/maven2/${request.coordinate.name}.${if (request.variant.platform in setOf("jvm", "common")) "jar" else "klib"}", digest(request.coordinate.toString() +
                    if (changedTool && request.domain.purpose == "sqldelight-compiler") " changed" else ""))
            ScopedSelection(request.domain, LockedSelection(request.coordinate, request.variant, listOf(artifact),
                listOf(MetadataIdentity("https://repo.maven.apache.org/maven2/${request.coordinate.name}.pom", digest("metadata")))))
        })
        return projectActionGraph(effective, lock, files, digest("runtime"), tools,
            ProjectGraphRequest(modules, targets, mode))
    }
    private fun resolve(graph: ProjectActionGraph, emptyGeneration: Boolean = false, invalidGeneration: Boolean = false): Map<String, ActionSpec> {
        val results = mutableMapOf<String, ActionResult>()
        return graph.nodes.filterNot { it.id.endsWith(":package") }.associate { node ->
            val action = node.resolve(results.filterKeys { it in node.dependencies })
            val output = when {
                action.implementation == "kvik-jvm-metadata-v1" -> listOf(FileEntry(LogicalPath("metadata/default/manifest"), digest(action.key(Sha256).toString())))
                action.implementation == "kvik-documentation-native-stdlib-v1" -> listOf(FileEntry(LogicalPath("stdlib/default/manifest"), digest("stdlib")))
                action.implementation == "kvik-native-compiler-v1" -> NativeCompilation.fromJson(action.parameters).let {
                    listOf(FileEntry(it.output, digest(action.key(Sha256).toString()), action.kind == ActionKind.LINK))
                }
                action.implementation == "kvik-js-compiler-v1" -> JsCompilation.fromJson(action.parameters).let {
                    listOf(FileEntry(if (it.includedLibrary == null) it.output else LogicalPath("${it.output.value}/${it.moduleName}.mjs"), digest(action.key(Sha256).toString())))
                }
                node.id.endsWith(":documentation") -> listOf(FileEntry(LogicalPath("html/index.html"), digest("documentation")))
                node.id.endsWith(":sqldelight") -> if (emptyGeneration && node.id.startsWith("data:")) emptyList() else {
                    val prefix = if (invalidGeneration) "other-output" else "generated/sqldelight/${node.id.substringBefore(':')}"
                    listOf(FileEntry(LogicalPath("$prefix/Database.kt"), digest(action.key(Sha256).toString())))
                }
                else -> listOf(FileEntry(LogicalPath(JvmCompilation.fromJson(action.parameters).output.value + if (node.id.endsWith(":sqldelight-worker")) "/kvik/sqldelight/Generate.class" else "/Main.class"), digest(action.key(Sha256).toString())))
            }
            results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED, TreeManifest(output), 0)
            node.id to action
        }
    }

    @Test fun generatedSourceHasExplicitPrerequisiteAndToolIsolation() {
        val graph = graph()
        assertEquals(setOf("data:jvm:sqldelight"), graph.nodes.single { it.id == "data:jvm:main" }.dependencies)
        assertEquals(setOf("data:jvm:sqldelight-worker"), graph.nodes.single { it.id == "data:jvm:sqldelight" }.dependencies)
        val actions = resolve(graph)
        val compile = JvmCompilation.fromJson(actions.getValue("data:jvm:main").parameters)
        assertTrue(LogicalPath("generated/sqldelight/data/Database.kt") in compile.sources)
        assertTrue(compile.classpath.size == 1)
        assertFalse(actions.getValue("data:jvm:main").inputs.files.any { it.path.value.endsWith(".sq") || it.path.value.startsWith("tools/") })
        val generation = SqlDelightGeneration.fromJson(actions.getValue("data:jvm:sqldelight").parameters)
        val origin = graph.generatedSources.single { it.module == "data" }
        assertEquals(generation.output, origin.outputRoot)
        assertEquals(generation.settings.sourceSet, origin.sourceSet)
        assertEquals(listOf(origin.outputRoot), actions.getValue(origin.producer).outputs)
        assertEquals(listOf(LogicalPath("data/sql/fixture/Tables.sq")), generation.sources)
        assertEquals(LogicalPath("tools/sqldelight-worker"), generation.worker)
        assertEquals(actions.getValue("data:jvm:sqldelight-worker").key(Sha256), actions.getValue("other:jvm:sqldelight-worker").key(Sha256))
    }

    @Test fun sqlOptionsAndToolChangesInvalidateOnlyTheirConsumers() {
        val initial = resolve(graph())
        val edited = resolve(graph(files = files(sql = "edited SQL")))
        val options = resolve(graph(configuration = configuration(settings.copy(packageName = "fixture.changed"))))
        for (changed in listOf(edited, options)) {
            assertNotEquals(initial.getValue("data:jvm:sqldelight").key(Sha256), changed.getValue("data:jvm:sqldelight").key(Sha256))
            assertNotEquals(initial.getValue("data:jvm:main").key(Sha256), changed.getValue("data:jvm:main").key(Sha256))
            assertEquals(initial.getValue("data:jvm:sqldelight-worker").key(Sha256), changed.getValue("data:jvm:sqldelight-worker").key(Sha256))
            assertEquals(initial.getValue("plain:jvm:main").key(Sha256), changed.getValue("plain:jvm:main").key(Sha256))
        }
        assertEquals(initial.getValue("other:jvm:main").key(Sha256), edited.getValue("other:jvm:main").key(Sha256))
        val changedTool = resolve(graph(changedTool = true))
        assertNotEquals(initial.getValue("data:jvm:sqldelight-worker").key(Sha256), changedTool.getValue("data:jvm:sqldelight-worker").key(Sha256))
        assertNotEquals(initial.getValue("data:jvm:sqldelight").key(Sha256), changedTool.getValue("data:jvm:sqldelight").key(Sha256))
        assertEquals(initial.getValue("plain:jvm:main").key(Sha256), changedTool.getValue("plain:jvm:main").key(Sha256))
    }

    @Test fun supportsGeneratedOnlySourcesAndRemovesDeletedSqlOutputs() {
        val generatedOnly = resolve(graph(files = files(includeKotlin = false)))
        assertEquals(listOf(LogicalPath("generated/sqldelight/data/Database.kt")), JvmCompilation.fromJson(generatedOnly.getValue("data:jvm:main").parameters).sources)
        val deleted = resolve(graph(files = files(includeSql = false)), emptyGeneration = true)
        assertEquals(listOf(LogicalPath("src/data/src/Main.kt")), JvmCompilation.fromJson(deleted.getValue("data:jvm:main").parameters).sources)
        assertTrue(SqlDelightGeneration.fromJson(deleted.getValue("data:jvm:sqldelight").parameters).sources.isEmpty())
        assertFailsWith<IllegalArgumentException> { resolve(graph(files = files(includeSql = false, includeKotlin = false)), emptyGeneration = true) }
    }


    @Test fun documentationIncludesGeneratedApiAndRemovesDeletedSqlSources() {
        val config = configuration(documentation = true)
        fun build(files: TreeManifest, empty: Boolean = false) = resolve(graph(config, files, mode = ProjectMode.DOCUMENTATION), emptyGeneration = empty)
        val built = graph(config, files(includeKotlin = false), mode = ProjectMode.DOCUMENTATION)
        assertContains(built.nodes.single { it.id == "data:common:documentation" }.dependencies, "data:jvm:sqldelight")
        val initial = resolve(built).getValue("data:common:documentation")
        val source = LogicalPath("sources/main/sqldelight/Database.kt")
        assertTrue(initial.inputs.files.any { it.path == source })
        assertFalse(initial.inputs.files.any { it.path.value.endsWith(".sq") || it.path.value.startsWith("tools/") })
        val spec = DocumentationSpec.fromJson(initial.parameters)
        assertEquals("main", spec.sourceSets.single().name)
        assertEquals("jvm", spec.sourceSets.single().platform)
        assertEquals(LogicalPath("sources/main"), spec.sourceSets.single().sourceRoot)
        val edited = build(files(sql = "changed SQL", includeKotlin = false)).getValue("data:common:documentation")
        assertNotEquals(initial.key(Sha256), edited.key(Sha256))
        val deleted = build(files(includeSql = false), empty = true).getValue("data:common:documentation")
        assertFalse(deleted.inputs.files.any { it.path.value.startsWith("sources/main/sqldelight/") })
        assertTrue(deleted.inputs.files.any { it.path.value == "sources/main/root0/Main.kt" })
    }


    private fun multiplatform(targets: Set<String>, sqlSource: String = "commonMain", generatedOnly: Boolean = false,
        publication: Boolean = false, dependency: Boolean = false): ProjectConfiguration {
        fun module(id: String, database: Boolean): JsonValue.Object {
            fun set(name: String, parents: List<String> = emptyList(), dependency: Boolean = false) = obj(
                "name" to name.json(), "directories" to (if (generatedOnly && database) emptyList() else listOf("$id/$name".json())).json(),
                "resources" to emptyList<JsonValue>().json(), "dependsOn" to parents.map { it.json() }.json(),
                "dependencies" to (if (dependency) listOf(obj("module" to "data".json(), "exported" to true.json())) else emptyList()).json())
            return obj("id" to id.json(), "sourceSets" to (listOf(set("commonMain", dependency = !database)) + targets.flatMap {
                listOf(set("${it}Main", listOf("commonMain")), set("${it}Test", listOf("${it}Main")))
            }).json(), "targets" to targets.map { obj("name" to it.json(), "main" to "${it}Main".json(), "test" to "${it}Test".json(),
                "compilerOptions" to emptyList<JsonValue>().json()) }.json(), "compilerPlugins" to emptyList<JsonValue>().json(),
                "documentation" to DocumentationSettings().toJson()).let { base -> JsonValue.Object(base.values +
                    (if (database) mapOf("sqlDelight" to settings.copy(sourceSet = sqlSource).toJson()) else emptyMap()) +
                    (if (publication) mapOf("publication" to obj("coordinate" to "fixture:$id:1".json())) else emptyMap())) }
        }
        return parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "kmpSql".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
            "modules" to (listOf(module("data", true)) + if (dependency) listOf(module("consumer", false)) else emptyList()).json()))
    }

    private fun multiplatformFiles(targets: Set<String>, sql: String = "schema", generatedOnly: Boolean = false, dependency: Boolean = false) = TreeManifest(buildList {
        add(FileEntry(LogicalPath("data/sql/fixture/Tables.sq"), digest(sql)))
        for (module in listOf("data") + if (dependency) listOf("consumer") else emptyList()) {
            if (generatedOnly && module == "data") continue
            for (set in listOf("commonMain") + targets.flatMap { listOf("${it}Main", "${it}Test") })
                add(FileEntry(LogicalPath("$module/$set/Source.kt"), digest("$module/$set")))
        }
    })

    @Test fun sharedSqlGenerationFeedsEveryTargetAndTheirRecompiledTests() {
        val targets = setOf("jvm", "macosArm64", "js", "wasmJs")
        val config = multiplatform(targets)
        val plan = graph(config, multiplatformFiles(targets), mode = ProjectMode.COMPILE_TESTS, targets = targets, modules = setOf("data"))
        assertEquals(1, plan.nodes.count { it.id.endsWith(":sqldelight") })
        val actions = resolve(plan)
        val generated = LogicalPath("generated/sqldelight/data/Database.kt")
        for (target in targets) {
            assertContains(plan.nodes.single { it.id == "data:$target:main" }.dependencies, "data:jvm:sqldelight")
            val main = actions.getValue("data:$target:main")
            assertContains(main.inputs.files.map { it.path }, generated)
            if (target == "jvm") {
                assertContains(JvmCompilation.fromJson(main.parameters).commonSources, generated)
                assertFalse(actions.getValue("data:jvm:test-compile").inputs.files.any { it.path == generated })
            } else if (target == "macosArm64") {
                for (id in listOf("data:$target:main", "data:$target:test-compile")) {
                    val spec = NativeCompilation.fromJson(actions.getValue(id).parameters)
                    assertContains(spec.sources, generated)
                    assertContains(spec.commonSources, generated)
                    assertContains(spec.sourceFragments.single { it.name == "commonMain" }.sources, generated)
                    assertEquals(1, spec.sourceFragments.count { generated in it.sources })
                }
            } else for (id in listOf("data:$target:main", "data:$target:test-compile")) {
                val spec = JsCompilation.fromJson(actions.getValue(id).parameters)
                assertContains(spec.sources, generated)
                assertContains(spec.commonSources, generated)
            }
        }
        val generatedOnly = resolve(graph(multiplatform(targets, generatedOnly = true), multiplatformFiles(targets, generatedOnly = true),
            targets = targets, modules = setOf("data")))
        for (target in targets) {
            val parameters = generatedOnly.getValue("data:$target:main").parameters
            val sources = when (target) {
                "jvm" -> JvmCompilation.fromJson(parameters).sources
                "macosArm64" -> NativeCompilation.fromJson(parameters).sources
                else -> JsCompilation.fromJson(parameters).sources
            }
            assertEquals(listOf(generated), sources)
        }
        val changed = resolve(graph(config, multiplatformFiles(targets, sql = "edited"), mode = ProjectMode.COMPILE_TESTS, targets = targets, modules = setOf("data")))
        for (target in targets) for (scope in listOf("main", "test-compile"))
            assertNotEquals(actions.getValue("data:$target:$scope").key(Sha256), changed.getValue("data:$target:$scope").key(Sha256))
    }

    @Test fun platformSqlDoesNotLeakIntoOtherTargetsAndAdmitsHostWithoutJvmTarget() {
        val targets = setOf("macosArm64", "js", "wasmJs")
        val config = multiplatform(targets, sqlSource = "jsMain")
        fun request(target: String) = ProjectGraphRequest(setOf("data"), setOf(target), ProjectMode.BUILD)
        assertEquals(setOf("js", "jvm"), projectToolTargets(config.project, request("js")))
        assertEquals(setOf("wasmJs"), projectToolTargets(config.project, request("wasmJs")))
        val initial = resolve(graph(config, multiplatformFiles(targets), targets = targets, modules = setOf("data")))
        val edited = resolve(graph(config, multiplatformFiles(targets, sql = "edited"), targets = targets, modules = setOf("data")))
        assertNotEquals(initial.getValue("data:js:main").key(Sha256), edited.getValue("data:js:main").key(Sha256))
        assertFalse(JsCompilation.fromJson(initial.getValue("data:js:main").parameters).commonSources.any { it.value.startsWith("generated/") })
        for (target in setOf("macosArm64", "wasmJs")) {
            assertEquals(initial.getValue("data:$target:main").key(Sha256), edited.getValue("data:$target:main").key(Sha256))
            assertFalse(initial.getValue("data:$target:main").inputs.files.any { it.path.value.startsWith("generated/") })
        }
        val isolated = graph(config, multiplatformFiles(targets), targets = setOf("wasmJs"), modules = setOf("data"))
        assertFalse(isolated.nodes.any { it.id.contains("sqldelight") })
    }

    @Test fun generatedOnlyCommonSourcesArePublishedAndAvailableToDocumentationDependencies() {
        val targets = setOf("js", "wasmJs")
        val config = multiplatform(targets, generatedOnly = true, publication = true, dependency = true)
        val files = multiplatformFiles(targets, generatedOnly = true, dependency = true)
        val publication = graph(config, files, mode = ProjectMode.PACKAGE, targets = targets, modules = setOf("data"))
        val metadata = resolve(publication).getValue("data:common:metadata")
        assertEquals(listOf(LogicalPath("generated/sqldelight/data/Database.kt")), JvmMetadataCompilation.fromJson(metadata.parameters).sources)
        assertContains(publication.nodes.single { it.id == "data:common:package" }.dependencies, "data:common:metadata")
        val docs = resolve(graph(config, files, mode = ProjectMode.DOCUMENTATION, targets = targets, modules = setOf("consumer")))
        val dependencyMetadata = JvmMetadataCompilation.fromJson(docs.getValue("data:common:documentation-metadata-js-wasmJs").parameters)
        assertEquals(listOf(LogicalPath("generated/sqldelight/data/Database.kt")), dependencyMetadata.sources)
        val ownDocs = resolve(graph(config, files, mode = ProjectMode.DOCUMENTATION, targets = targets, modules = setOf("data"))).getValue("data:common:documentation")
        assertContains(ownDocs.inputs.files.map { it.path }, LogicalPath("sources/commonMain/sqldelight/Database.kt"))
    }

    @Test fun rejectsUnownedOutputsAndUnsupportedSqlInputs() {
        assertFailsWith<IllegalArgumentException> { resolve(graph(), invalidGeneration = true) }
        for (extension in listOf("sqm", "db", "txt")) assertFailsWith<IllegalArgumentException> {
            graph(files = TreeManifest(files().files + FileEntry(LogicalPath("data/sql/1.$extension"), digest("unsupported"))))
        }
        val graph = graph()
        val node = graph.nodes.single { it.id == "data:jvm:main" }
        assertFailsWith<IllegalArgumentException> { node.resolve(mapOf("data:jvm:sqldelight" to ActionResult("data:jvm:sqldelight", digest("key"), ActionStatus.FAILED, TreeManifest(emptyList()), 1))) }
    }
}
