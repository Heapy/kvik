package kvik.packages

import kvik.core.*
import kvik.cache.*
import kvik.locking.*
import kvik.resolution.*
import kotlin.test.*

class GraphsTest {
    private val variant = jvmVariantRequest("java-runtime", 11)
    @Test fun sqlDelightCompilerDeclarationsAndLockedGraphRemainVisibleWithoutConsumerLeakage() {
        val document = project(listOf("example:consumer:1.0")).document.objectValue()
        val module = document.getValue("modules").arrayValue().single().objectValue()
        val settings = SqlDelightSpec("main", LogicalPath("schema"), "example.db", "Database")
        val configuration = parseProjectConfiguration(JsonValue.Object(document + ("modules" to
            listOf(JsonValue.Object(module + ("sqlDelight" to settings.toJson()))).json())))
        val effective = effectiveConfiguration(configuration)
        val tools = sqlDelightCompilerCoordinates(settings.version)
        val entries = effective.requests.flatMap { component(it.coordinate.toString()).entries }.associate { it.toPair() }
        val content = MemoryContent()
        val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(entries))
        val writes = content.writes
        fun query(filter: ProjectQueryFilter = ProjectQueryFilter()) = queryProjectDependencies("/project/kvik.json",
            "/project/kvik.lock.json", "/project/cache", configuration, effective, { lock.bytes() }, content, NativePomParser(), 1000, filter)
        val result = query()
        assertEquals("complete", result.values.getValue("status").stringValue())
        val declarations = result.values.getValue("declarations").arrayValue().map { it.objectValue() }
            .filter { it.getValue("kind").stringValue() == "sqldelight-compiler" }
        assertEquals(tools.map { it.toString() }.toSet(), declarations.map { it.getValue("coordinate").stringValue() }.toSet())
        assertTrue(declarations.all { it.getValue("sourceSet") == "main".json() && it.getValue("scope") == "runtime".json() &&
            it.getValue("targets") == listOf("jvm".json()).json() && it.getValue("compilations") == listOf("main".json()).json() &&
            !it.getValue("exported").booleanValue() })
        val domains = result.values.getValue("domains").arrayValue().map { it.objectValue() }
        val host = domains.single { it.getValue("domain").objectValue().getValue("purpose").stringValue() == "sqldelight-compiler" }
        assertEquals(COMPILER_PLUGIN_JVM_HOST.toString(), host.getValue("graph").objectValue().getValue("variant")
            .objectValue().getValue("attributes").objectValue().getValue(JVM_TARGET_VERSION_ATTRIBUTE).stringValue())
        val api = query(ProjectQueryFilter(usage = "java-api"))
        assertTrue(api.values.getValue("domains").arrayValue().all {
            it.objectValue().getValue("domain").objectValue().getValue("purpose").stringValue() == "dependencies"
        })
        // Filtering hides display rows only: missing compiler artifacts invalidate the entire lock.
        content.entries.remove(lock.selections.first { it.domain.purpose == "sqldelight-compiler" }.artifacts.single().digest)
        assertEquals("incomplete", query(ProjectQueryFilter(usage = "java-api")).values.getValue("status").stringValue())
        assertEquals(writes, content.writes)
    }
    @Test fun iosPackageVariantsAndProjectFiltersReplayExactTargetArtifacts() {
        val root = Coordinate.parse("example:root:1.0")
        val base = "${repository.url}/example/root/1.0"
        val targets = listOf(NativeTarget.IOS_ARM64, NativeTarget.IOS_SIMULATOR_ARM64)
        val blobs = targets.associate { "$base/${it.compilerName}.klib" to it.compilerName.encodeToByteArray() }
        val variants = targets.map { target ->
            val artifact = "${target.compilerName}.klib"
            val digest = Sha256.digest(blobs.getValue("$base/$artifact"))
            obj("name" to target.projectName.json(), "attributes" to obj("org.gradle.category" to "library".json(),
                "org.gradle.usage" to "kotlin-api".json(), "org.jetbrains.kotlin.platform.type" to "native".json(),
                "org.jetbrains.kotlin.native.target" to target.compilerName.json()),
                "files" to listOf(obj("name" to artifact.json(), "url" to artifact.json(), "size" to digest.sizeBytes.json(), "sha256" to digest.hash.json())).json())
        }
        val entries = blobs + ("$base/root-1.0.module" to obj("formatVersion" to "1.1".json(),
            "component" to obj("group" to root.group.json(), "module" to root.name.json(), "version" to root.version.json()),
            "variants" to variants.json()).canonical().encodeToByteArray())
        for (target in targets) {
            val cache = MemoryCache()
            val network = MemoryNetwork(entries)
            val request = VariantRequest(target.projectName, "kotlin-api", emptyMap())
            fun query(mode: QueryMode) = queryPackageDependencies("/project/kvik.json", root, request, listOf(repository), emptyList(),
                QueryTransport(listOf(repository), cache, network, mode, 1000), NativePomParser())
            val first = query(QueryMode.CACHE)
            assertEquals("complete", first.values.getValue("status").stringValue())
            val graph = first.values.getValue("graph").objectValue()
            val artifact = graph.getValue("nodes").arrayValue().single().objectValue().getValue("artifacts").arrayValue().single().objectValue()
            assertEquals("$base/${target.compilerName}.klib", artifact.getValue("url").stringValue())
            val calls = network.calls.size; val writes = cache.writes
            assertEquals(first.values.getValue("graph"), query(QueryMode.OFFLINE).values.getValue("graph"))
            assertEquals(calls, network.calls.size); assertEquals(writes, cache.writes)
        }
        val original = project(listOf(root.toString())).document.objectValue()
        val module = original.getValue("modules").arrayValue().single().objectValue()
        val configured = JsonValue.Object(module + ("targets" to targets.map { target -> obj("name" to target.projectName.json(),
            "main" to "main".json(), "test" to JsonValue.Null, "compilerOptions" to emptyList<JsonValue>().json()) }.json()))
        val configuration = parseProjectConfiguration(JsonValue.Object(original + ("modules" to listOf(configured).json())))
        val effective = effectiveConfiguration(configuration)
        val content = MemoryContent()
        val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(entries))
        val writes = content.writes
        fun query(filter: ProjectQueryFilter) = queryProjectDependencies("/project/kvik.json", "/project/kvik.lock.json", "/project/cache",
            configuration, effective, { lock.bytes() }, content, NativePomParser(), 1000, filter)
        for (target in targets) {
            val result = query(ProjectQueryFilter(targets = listOf(target.projectName), compilation = "main", usage = "kotlin-api"))
            assertEquals("complete", result.values.getValue("status").stringValue())
            val domain = result.values.getValue("domains").arrayValue().single().objectValue().getValue("domain")
            assertEquals(ResolutionDomain("sample", target.projectName, "main", "kotlin-api"), ResolutionDomain.fromJson(domain))
        }
        assertEquals(4, query(ProjectQueryFilter(targets = targets.map { it.projectName })).values.getValue("domains").arrayValue().size)
        val hidden = lock.selections.first { it.domain.target == "iosArm64" }.artifacts.single().digest
        content.entries.remove(hidden)
        assertEquals("incomplete", query(ProjectQueryFilter(targets = listOf("iosSimulatorArm64"))).values.getValue("status").stringValue())
        assertEquals(writes, content.writes)
        assertFails { ProjectQueryFilter(targets = listOf("iosX64")) }
        assertFails { ProjectQueryFilter(targets = listOf("iosArm64", "iosArm64")) }
        assertFails { query(ProjectQueryFilter(targets = listOf("linuxX64"))) }
    }

    private fun resolver(entries: Map<String, ByteArray>, pins: List<Coordinate> = emptyList()) = MavenResolver(MemoryNetwork(entries), NativePomParser(), pins)

    @Test fun originalRequirementsAndPinSelectionsHaveDeterministicShortestPaths() {
        val root = Coordinate.parse("example:root:1.0"); val leaf = Coordinate.parse("example:leaf:2.0")
        val entries = component(root.toString(), listOf("example:left:1.0", "example:right:1.0")) +
            component("example:left:1.0", listOf("example:leaf:1.0")) + component("example:right:1.0", listOf("example:leaf:1.0")) + component(leaf.toString())
        val graph = resolver(entries, listOf(leaf)).resolveGraph(listOf(root), variant, listOf(repository))
        assertEquals(resolver(entries, listOf(leaf)).resolveAll(listOf(root), variant, listOf(repository)), graph.selections)
        assertEquals(4, graph.nodes.size); assertEquals(4, graph.edges.size)
        assertEquals(2, graph.edges.count { it.requirement.toString() == "example:leaf:1.0" && it.selected == leaf })
        val paths = graph.toJson().values.getValue("paths").arrayValue()
        assertEquals(4, paths.size)
        val path = paths.single { it.objectValue().getValue("node").stringValue().startsWith("example:leaf:") }.objectValue()
        assertEquals(3, path.getValue("nodes").arrayValue().size); assertEquals(2, path.getValue("edges").arrayValue().size)
        assertTrue(graph.toJson(maxPaths = 2).values.getValue("truncated").booleanValue())
        assertEquals(graph.toJson(), resolver(entries, listOf(leaf)).resolveGraph(listOf(root), variant, listOf(repository)).toJson())
        save("graph-pins.json", queryPackageDependencies("/project/kvik.json", root, variant, listOf(repository), listOf(leaf),
            QueryTransport(listOf(repository), MemoryCache(), MemoryNetwork(entries), QueryMode.CACHE, 1000), NativePomParser()))
    }
    @Test fun queryEdgeBoundsDoNotChangeOrdinaryLockResolution() {
        val root = Coordinate.parse("example:root:1.0")
        val entries = (component(root.toString(), List(8193) { "example:leaf:1.0" }) + component("example:leaf:1.0")).toMutableMap()
        val url = entries.keys.single { it.endsWith("/root-1.0.module") }
        val metadata = parseJson(entries.getValue(url).decodeToString()).objectValue()
        // One producer variant keeps this adversarial edge set below the metadata byte bound.
        entries[url] = JsonValue.Object(metadata + ("variants" to metadata.getValue("variants").arrayValue().filter {
            it.objectValue().getValue("name").stringValue() == "java-runtime"
        }.json())).canonical().encodeToByteArray()
        assertEquals(2, resolver(entries).resolveAll(listOf(root), variant, listOf(repository)).single().artifacts.size)
        val result = queryPackageDependencies("/project/kvik.json", root, variant, listOf(repository), emptyList(),
            QueryTransport(listOf(repository), MemoryCache(), MemoryNetwork(entries), QueryMode.CACHE, 1000), NativePomParser())
        assertEquals("incomplete", result.values.getValue("status").stringValue())
        assertTrue(result.values.getValue("truncated").booleanValue())
        assertEquals(JsonValue.Null, result.values.getValue("graph"))
        save("graph-bound.json", result)
        val configuration = project(listOf(root.toString()))
        val effective = effectiveConfiguration(configuration, scopes = listOf(DependencyScope("sample", "jvm", "main", "java-runtime")))
        val content = MemoryContent(); val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(entries))
        val projectResult = queryProjectDependencies("/project/kvik.json", "/project/kvik.lock.json", "/project/cache", configuration, effective, { lock.bytes() }, content, NativePomParser(), 1000)
        assertEquals("incomplete", projectResult.values.getValue("status").stringValue())
        assertTrue(projectResult.values.getValue("truncated").booleanValue())
        assertEquals("KVIK_QUERY_BOUND", projectResult.values.getValue("diagnostics").arrayValue().single().objectValue().getValue("code").stringValue())
        save("project-bound.json", projectResult)
    }
    @Test fun edgeLocalExclusionRetainsOrdinaryExpansionInBothOrders() {
        fun pom(name: String, excluded: Boolean): Pair<String, ByteArray> {
            val url = "${repository.url}/example/$name/1.0/$name-1.0.pom"
            return url to "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>$name</artifactId><version>1.0</version><dependencies><dependency><groupId>example</groupId><artifactId>middle</artifactId><version>1.0</version>${if (excluded) "<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>" else ""}</dependency></dependencies></project>".encodeToByteArray()
        }
        val entries = component("example:middle:1.0", listOf("example:leaf:1.0")) + component("example:leaf:1.0") +
            listOf(pom("excluded", true), pom("ordinary", false)).toMap() + mapOf(
                "${repository.url}/example/excluded/1.0/excluded-1.0.jar" to byteArrayOf(1), "${repository.url}/example/ordinary/1.0/ordinary-1.0.jar" to byteArrayOf(2))
        val roots = listOf("example:excluded:1.0", "example:ordinary:1.0").map(Coordinate::parse)
        for (ordered in listOf(roots, roots.reversed())) {
            val graph = resolver(entries).resolveGraph(ordered, variant, listOf(repository))
            assertEquals(setOf(false, true), graph.nodes.filter { it.coordinate.name == "middle" }.map { it.expanded }.toSet())
            val edge = graph.edges.single { it.source.startsWith("example:excluded:") }
            assertFalse(edge.transitive); assertEquals("pom-effective", edge.semantics)
            assertFalse(graph.edges.any { it.source == edge.target })
            assertEquals(setOf("ordinary", "middle", "leaf"), graph.selections.single { it.requested.name == "ordinary" }.artifacts.map { it.coordinate.name }.toSet())
            assertEquals(setOf("excluded", "middle"), graph.selections.single { it.requested.name == "excluded" }.artifacts.map { it.coordinate.name }.toSet())
        }
    }
    @Test fun actualJunitBomAndKotlinRedirectGraphsPreservePinnedFixtureSelections() {
        val transport = fixtureTransport(); val cache = MemoryCache()
        val root = Coordinate.parse("org.junit.jupiter:junit-jupiter-api:5.13.4")
        for (usage in listOf("java-api", "java-runtime")) {
            val result = queryPackageDependencies("/project/kvik.json", root, jvmVariantRequest(usage, 11), listOf(repository), emptyList(),
                QueryTransport(listOf(repository), cache, transport, QueryMode.CACHE, 1000), NativePomParser())
            assertEquals("complete", result.values.getValue("status").stringValue())
            val graph = result.values.getValue("graph").objectValue()
            assertTrue(graph.getValue("nodes").arrayValue().any { it.objectValue().getValue("coordinate").stringValue() == "org.junit:junit-bom:5.13.4" && it.objectValue().getValue("platform").booleanValue() })
            assertTrue(graph.getValue("edges").arrayValue().any { it.objectValue().getValue("kind").stringValue() == "constraint" })
            val expected = parseJson(read("$testRoot/tests/resolution/fixtures/junit-artifacts.json").decodeToString()).objectValue()
            val artifacts = graph.getValue("nodes").arrayValue().flatMap { it.objectValue().getValue("artifacts").arrayValue() }
            assertEquals(if (usage == "java-api") 4 else 3, artifacts.size)
            artifacts.forEach { artifact -> val fields = artifact.objectValue()
                assertEquals(expected.getValue(fields.getValue("coordinate").stringValue()).objectValue().getValue("digest"), fields.getValue("digest"))
            }
            save("junit-$usage.json", result)
            val offline = queryPackageDependencies("/project/kvik.json", root, jvmVariantRequest(usage, 11), listOf(repository), emptyList(),
                QueryTransport(listOf(repository), cache, null, QueryMode.OFFLINE, 1001), NativePomParser())
            assertEquals(result.values.getValue("graph"), offline.values.getValue("graph"))
        }
        val kotlin = Coordinate.parse("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        val pins = listOf(Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20"))
        val native = queryPackageDependencies("/project/kvik.json", kotlin, VariantRequest("macosArm64", "kotlin-api", emptyMap()), listOf(repository), pins,
            QueryTransport(listOf(repository), MemoryCache(), transport, QueryMode.CACHE, 1000), NativePomParser())
        assertEquals("complete", native.values.getValue("status").stringValue())
        save("serialization-native.json", native)
        val graph = MavenResolver(transport, NativePomParser(), pins).resolveGraph(listOf(kotlin), variant, listOf(repository))
        assertTrue(graph.edges.any { it.kind == "redirect" && it.requirement.name == "kotlinx-serialization-json-jvm" })
        assertEquals(MavenResolver(transport, NativePomParser(), pins).resolveAll(listOf(kotlin), variant, listOf(repository)), graph.selections)
        val expected = parseJson(read("$testRoot/tests/resolution/fixtures/selections.json").decodeToString()).arrayValue().single {
            val fields = it.objectValue(); fields.getValue("requested").stringValue() == kotlin.toString() && fields.getValue("platform").stringValue() == "jvm"
        }.objectValue()
        assertEquals(expected.getValue("artifacts").arrayValue().map { it.objectValue().getValue("digest") }, graph.selections.single().artifacts.map { it.digest.toJson() })
    }
    @Test fun unavailableCorruptUnsupportedAndNativeMismatchAreExplicitNonpassingResults() {
        val root = Coordinate.parse("example:root:1.0")
        fun run(entries: Map<String, ByteArray>, mode: QueryMode = QueryMode.CACHE, requested: VariantRequest = variant) = queryPackageDependencies(
            "/project/kvik.json", root, requested, listOf(repository), emptyList(), QueryTransport(listOf(repository), MemoryCache(), MemoryNetwork(entries), mode, 1000), NativePomParser())
        assertEquals("incomplete", run(emptyMap()).values.getValue("status").stringValue())
        assertEquals("incomplete", run(emptyMap(), QueryMode.OFFLINE).values.getValue("status").stringValue())
        val entries = component(root.toString())
        assertEquals("unsupported", run(entries, requested = VariantRequest("macosArm64", "kotlin-api", emptyMap())).values.getValue("status").stringValue())
        val artifact = entries.keys.single { it.endsWith(".jar") }
        val corrupt = run(entries + (artifact to byteArrayOf(5)))
        assertEquals("incomplete", corrupt.values.getValue("status").stringValue()); assertEquals(JsonValue.Null, corrupt.values.getValue("graph"))
        save("graph-corrupt.json", corrupt)
    }
    @Test fun projectScopedPinsReplayExactLocksWithoutWritesAndFiltersCannotHideCorruption() {
        val configuration = project(listOf("example:main:1.0"), listOf("example:tests:1.0"))
        val policy = ResolverPolicy(scopedPins = listOf("java-api", "java-runtime").map { ScopedPin(ResolutionDomain("sample", "jvm", "test", it), Coordinate.parse("example:annotations:23.0")) })
        val effective = effectiveConfiguration(configuration, policy)
        val entries = component("example:main:1.0", listOf("example:annotations:13.0")) + component("example:tests:1.0", listOf("example:annotations:23.0")) +
            component("example:annotations:13.0") + component("example:annotations:23.0")
        val content = MemoryContent(); val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(entries)); val writes = content.writes
        fun query(filter: ProjectQueryFilter = ProjectQueryFilter(), bytes: ByteArray = lock.bytes()) = queryProjectDependencies("/project/kvik.json", "/project/kvik.lock.json", "/project/cache",
            configuration, effective, { bytes }, content, NativePomParser(), 1000, filter)
        val result = query(); assertEquals("complete", result.values.getValue("status").stringValue()); assertEquals(writes, content.writes)
        val graphs = result.values.getValue("domains").arrayValue()
        assertEquals(4, graphs.size)
        for (value in graphs) {
            val domain = value.objectValue().getValue("domain").objectValue()
            val selected = value.objectValue().getValue("graph").objectValue().getValue("nodes").arrayValue().map { it.objectValue().getValue("coordinate").stringValue() }
            assertTrue("example:annotations:${if (domain.getValue("compilation").stringValue() == "main") "13.0" else "23.0"}" in selected)
        }
        assertEquals(1, query(ProjectQueryFilter(compilation = "main", usage = "java-api")).values.getValue("domains").arrayValue().size)
        val unusedTestBlob = lock.selections.filter { it.domain.compilation == "test" }.flatMap { it.artifacts }.first { it.coordinate.name == "tests" }.digest
        val original = content.entries.remove(unusedTestBlob)!!
        assertEquals("incomplete", query(ProjectQueryFilter(compilation = "main")).values.getValue("status").stringValue())
        content.entries[unusedTestBlob] = byteArrayOf(7)
        assertEquals("incomplete", query(ProjectQueryFilter(compilation = "main")).values.getValue("status").stringValue())
        assertContentEquals(byteArrayOf(7), content.entries.getValue(unusedTestBlob)); assertEquals(writes, content.writes)
        content.entries[unusedTestBlob] = original
        assertEquals("incomplete", query(bytes = lock.copy(configuration = Sha256.digest(byteArrayOf(4))).bytes()).values.getValue("status").stringValue())
        assertEquals("incomplete", query(bytes = byteArrayOf(1)).values.getValue("status").stringValue())
        assertFails { query(ProjectQueryFilter(modules = listOf("unknown"))) }
        save("project-scoped.json", result)
        NativeFiles.ensureDirectory("$testWork/project")
        NativeFiles.writeNew("$testWork/project/kvik.json", configuration.document.canonical().encodeToByteArray())
        NativeFiles.writeNew("$testWork/project/kvik.lock.json", lock.bytes())
        val disk = NativeContentStore("$testWork/project/cache")
        content.entries.values.forEach { disk.put(it) }
    }
    @Test fun moduleVisibilityAndRuntimeOnlyDeclarationsMatchTheirLockedDomains() {
        val base = project(emptyList()).document.objectValue()
        val template = base.getValue("modules").arrayValue().single().objectValue()
        fun external(name: String, exported: Boolean = false, runtime: Boolean = false) = obj("coordinate" to "example:$name:1.0".json(),
            "exported" to exported.json(), "scope" to (if (runtime) "runtime" else "compile").json())
        fun module(id: String, dependencies: List<JsonValue>): JsonValue {
            val source = template.getValue("sourceSets").arrayValue().first().objectValue()
            return JsonValue.Object(template + mapOf("id" to id.json(), "sourceSets" to listOf(JsonValue.Object(source + ("dependencies" to dependencies.json())),
                template.getValue("sourceSets").arrayValue().last()).json()))
        }
        val configuration = parseProjectConfiguration(JsonValue.Object(base + ("modules" to listOf(
            module("sample", listOf(obj("module" to "library".json()), obj("module" to "runtimeOnly".json(), "scope" to "runtime".json()))),
            module("library", listOf(external("private"), external("public", exported = true), external("runtimeHelper", runtime = true))),
            module("runtimeOnly", listOf(external("runtimePayload")))).json())))
        val effective = effectiveConfiguration(configuration)
        val entries = listOf("private", "public", "runtimeHelper", "runtimePayload").flatMap { component("example:$it:1.0").entries }.associate { it.toPair() }
        val content = MemoryContent(); val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(entries))
        val result = queryProjectDependencies("/project/kvik.json", "/project/kvik.lock.json", "/project/cache", configuration, effective, { lock.bytes() }, content, NativePomParser(), 1000)
        assertEquals("complete", result.values.getValue("status").stringValue())
        fun names(module: String, usage: String) = result.values.getValue("domains").arrayValue().single {
            val domain = it.objectValue().getValue("domain").objectValue()
            domain.getValue("module").stringValue() == module && domain.getValue("compilation").stringValue() == "main" && domain.getValue("usage").stringValue() == usage
        }.objectValue().getValue("graph").objectValue().getValue("nodes").arrayValue().map {
            Coordinate.parse(it.objectValue().getValue("coordinate").stringValue()).name
        }.toSet()
        assertEquals(setOf("public"), names("sample", "java-api"))
        assertEquals(setOf("private", "public"), names("library", "java-api"))
        assertEquals(setOf("private", "public", "runtimeHelper", "runtimePayload"), names("sample", "java-runtime"))
        val declared = result.values.getValue("declarations").arrayValue().map { it.objectValue() }
        assertTrue(declared.any { it.getValue("dependencyModule") == "runtimeOnly".json() && it.getValue("scope") == "runtime".json() && it.getValue("kind") == "module".json() })
        assertTrue(declared.any { it.getValue("coordinate") == "example:private:1.0".json() && !it.getValue("exported").booleanValue() })
        save("project-visibility.json", result)
    }
    @Test fun projectHostDomainsRemainJVM25AndDeclarationsRetainNativeGeneratorRoles() {
        val original = project(emptyList())
        val document = original.document.canonical().replace("\"name\":\"jvm\"", "\"name\":\"macosArm64\",\"nativeTestSettings\":{\"engine\":\"kotest\",\"kspVersion\":\"2.3.12\",\"processor\":\"io.kotest:kotest-framework-symbol-processor:6.2.5\"}")
        val configuration = parseProjectConfiguration(parseJson(document)); val effective = effectiveConfiguration(configuration)
        val entries = effective.requests.flatMap { component(it.coordinate.toString()).entries }.associate { it.toPair() }
        val content = MemoryContent(); val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(entries))
        val result = queryProjectDependencies("/project/kvik.json", "/project/kvik.lock.json", "/project/cache", configuration, effective, { lock.bytes() }, content, NativePomParser(), 1000)
        assertEquals("complete", result.values.getValue("status").stringValue())
        val domains = result.values.getValue("domains").arrayValue()
        assertEquals(setOf("ksp-runtime", "symbol-processor"), domains.map { it.objectValue().getValue("domain").objectValue().getValue("purpose").stringValue() }.toSet())
        assertTrue(domains.all { it.objectValue().getValue("graph").objectValue().getValue("variant").objectValue().getValue("attributes").objectValue().getValue(JVM_TARGET_VERSION_ATTRIBUTE).stringValue() == "25" })
        assertEquals(4, result.values.getValue("declarations").arrayValue().size)
        save("project-ksp.json", result)
    }
}
