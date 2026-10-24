package kvik.core

import kotlin.test.*

class ChangeImpactTests {
    private val jvm = setOf("jvm")
    private fun path(value: String) = LogicalPath(value)
    private fun source(name: String, root: String, dependsOn: List<String> = emptyList(), dependencies: List<Dependency> = emptyList(),
                       resources: List<String> = emptyList()) =
        SourceSet(name, listOf(path(root)), resources.map(::path), dependsOn, dependencies)

    private fun module(id: String, dependencies: List<Dependency> = emptyList(), testDependencies: List<Dependency> = emptyList(),
                       plugins: List<CompilerPluginSpec> = emptyList(), targets: Set<String> = jvm) = ModuleSpec(id,
        listOf(source("main", "$id/src", dependencies = dependencies, resources = listOf("$id/resources")),
            source("test", "$id/test", dependencies = testDependencies, resources = listOf("$id/test-resources"))),
        targets.map { TargetSpec(it, "main", "test", emptyList()) }, plugins)

    private fun configuration(vararg modules: ModuleSpec) = ProjectConfiguration(ProjectSpec("fixture", emptyList(), modules.toList()), obj())
    private fun checks(modules: Set<String>, targets: Set<String> = jvm) = modules.flatMap { module -> targets.map { "$module:$it:test" } }.toSet()
    private fun selected(config: ProjectConfiguration, modules: Set<String>, vararg paths: String, targets: Set<String> = jvm,
                         completed: Set<String> = checks(modules, targets), full: Set<String> = emptySet()) =
        selectAffectedTests(config, modules, targets, paths.map(::path).toSet(), completed, full)

    private fun plugin(producer: String, targets: Set<String> = jvm, scopes: Set<String> = setOf("main"), inputs: List<String> = emptyList()) =
        CompilerPluginSpec(producer, null, targets, scopes, emptyList(), inputs.map(::path), "plugin.$producer", "2.4.20")

    @Test fun unchangedCompletedMatrixIsExplicitlyOmittedAndMissingBaselineChecksAlwaysRun() {
        val config = configuration(module("app"), module("lib"))
        val modules = setOf("app", "lib")
        val unchanged = selected(config, modules)
        assertEquals(checks(modules), unchanged.requested)
        assertTrue(unchanged.selected.isEmpty())
        assertTrue(unchanged.reasons.isEmpty())
        assertEquals(listOf("app:jvm:test", "lib:jvm:test"), unchanged.toJson().values.getValue("omittedChecks").arrayValue().map { it.stringValue() })
        val incomplete = selected(config, modules, completed = setOf("app:jvm:test"))
        assertEquals(setOf("lib:jvm:test"), incomplete.selected)
        assertEquals(listOf("baseline-missing"), incomplete.reasons.getValue("lib:jvm:test"))
        assertEquals(checks(modules), selected(config, modules, completed = emptySet()).selected)
    }

    @Test fun ownMainAlwaysAffectsTestsEvenWithoutTestSourceSetDependsOnMain() {
        val config = configuration(module("app"), module("other"))
        val result = selected(config, setOf("app", "other"), "app/src/New.kt")
        assertEquals(setOf("app:jvm:test"), result.selected)
        assertEquals(listOf("changed:app/src/New.kt"), result.reasons.getValue("app:jvm:test"))
        assertTrue(result.fullReasons.isEmpty())
    }

    @Test fun testOnlyInputsDoNotPropagateThroughOrdinaryModuleConsumers() {
        val library = module("library")
        val app = module("app", listOf(Dependency.Module("library")))
        val config = configuration(library, app)
        val modules = setOf("library", "app")
        assertEquals(setOf("library:jvm:test"), selected(config, modules, "library/test/Test.kt").selected)
        assertEquals(setOf("library:jvm:test"), selected(config, modules, "library/test-resources/data.json").selected)
        assertEquals(checks(modules), selected(config, modules, "library/src/Api.kt").selected)
        assertEquals(checks(modules), selected(config, modules, "library/resources/data.json").selected)
    }

    @Test fun transitivePrivateAndRuntimeDependenciesPropagateRegardlessOfCompileVisibility() {
        val leaf = module("leaf")
        val runtime = module("runtime", listOf(Dependency.Module("leaf")))
        val service = module("service", listOf(Dependency.Module("runtime", DependencyScopeKind.RUNTIME)))
        val app = module("app", listOf(Dependency.Module("service")))
        val config = configuration(app, service, runtime, leaf)
        val modules = setOf("app", "service", "runtime", "leaf")
        assertEquals(checks(modules), selected(config, modules, "leaf/src/Private.kt").selected)
        assertEquals(setOf("leaf:jvm:test"), selected(config, modules, "leaf/test/Test.kt").selected)
    }

    @Test fun testDependencyClosureBelongsOnlyToItsTestOwner() {
        val fixture = module("fixture")
        val library = module("library", testDependencies = listOf(Dependency.Module("fixture", DependencyScopeKind.RUNTIME)))
        val app = module("app", listOf(Dependency.Module("library")))
        val config = configuration(fixture, library, app)
        val modules = setOf("fixture", "library", "app")
        assertEquals(setOf("fixture:jvm:test", "library:jvm:test"), selected(config, modules, "fixture/src/Helper.kt").selected)
    }

    @Test fun sourceSetDependsOnSelectsOnlyTargetsWhoseClosuresContainTheChangedPath() {
        val sources = listOf(
            source("commonMain", "common", resources = listOf("common-resources")),
            source("intermediate", "intermediate", listOf("commonMain")),
            source("jvmMain", "jvm", listOf("intermediate")),
            source("nativeMain", "native", listOf("commonMain")),
            source("commonTest", "common-test", listOf("commonMain")),
            source("jvmTest", "jvm-test", listOf("jvmMain", "commonTest")),
            source("nativeTest", "native-test", listOf("nativeMain", "commonTest")),
        )
        val library = ModuleSpec("library", sources, listOf(TargetSpec("jvm", "jvmMain", "jvmTest", emptyList()),
            TargetSpec("macosArm64", "nativeMain", "nativeTest", emptyList())), emptyList())
        val config = configuration(library)
        val targets = setOf("jvm", "macosArm64")
        assertEquals(checks(setOf("library"), targets), selected(config, setOf("library"), "common/New.kt", targets = targets).selected)
        assertEquals(checks(setOf("library"), targets), selected(config, setOf("library"), "common-test/Test.kt", targets = targets).selected)
        assertEquals(setOf("library:jvm:test"), selected(config, setOf("library"), "intermediate/Api.kt", targets = targets).selected)
        assertEquals(setOf("library:macosArm64:test"), selected(config, setOf("library"), "native-test/Test.kt", targets = targets).selected)
    }

    @Test fun nativePluginConsumptionTracksJvmProducerRuntimeClosureAndNestedPlugins() {
        val nested = module("nested")
        val helper = module("helper", plugins = listOf(plugin("nested")))
        val producer = module("producer", listOf(Dependency.Module("helper", DependencyScopeKind.RUNTIME)))
        val app = module("app", plugins = listOf(plugin("producer", setOf("macosArm64"), inputs = listOf("plugin/schema.json"))),
            targets = setOf("jvm", "macosArm64"))
        val config = configuration(app, producer, helper, nested)
        val targets = setOf("jvm", "macosArm64")
        for (changed in listOf("producer/src/Plugin.kt", "helper/src/Runtime.kt", "nested/src/Plugin.kt", "plugin/schema.json", "plugin/schema.json/nested")) {
            val result = selected(config, setOf("app"), changed, targets = targets)
            assertEquals(setOf("app:macosArm64:test"), result.selected, changed)
            assertTrue(result.fullReasons.isEmpty(), changed)
        }
        for (changed in listOf("producer/test/Test.kt", "helper/test/Test.kt", "nested/test/Test.kt")) {
            assertTrue(selected(config, setOf("app"), changed, targets = targets).selected.isEmpty(), changed)
        }
    }

    @Test fun pluginScopeAndInputDirectoriesDistinguishProductionFromTests() {
        val producer = module("producer")
        val library = module("library", plugins = listOf(
            plugin("producer", scopes = setOf("main"), inputs = listOf("schemas/main")),
            plugin("producer", scopes = setOf("test"), inputs = listOf("schemas/test")),
        ))
        val app = module("app", listOf(Dependency.Module("library")))
        val config = configuration(app, library, producer)
        val modules = setOf("app", "library")
        assertEquals(checks(modules), selected(config, modules, "schemas/main/deleted.schema").selected)
        assertEquals(setOf("library:jvm:test"), selected(config, modules, "schemas/test/new.schema").selected)
        assertEquals(checks(modules), selected(config, modules, "producer/src/Plugin.kt").selected)
    }

    @Test fun publishedPluginsStillTrackTheirDeclaredInputFiles() {
        val plugin = CompilerPluginSpec(null, Coordinate("example", "plugin", "1"), jvm, setOf("test"), emptyList(), listOf(path("schemas/test.json")))
        val config = configuration(module("app", plugins = listOf(plugin)), module("other"))
        val result = selected(config, setOf("app", "other"), "schemas/test.json")
        assertEquals(setOf("app:jvm:test"), result.selected)
    }

    @Test fun deletedNewAndRenamedPathsAreMatchedLexicallyAndBothEndpointsMatter() {
        val config = configuration(module("app"), module("other"))
        val result = selected(config, setOf("app", "other"), "app/resources/deleted.json", "other/resources/new.json")
        assertEquals(setOf("app:jvm:test", "other:jvm:test"), result.selected)
        assertEquals(setOf(path("app/resources/deleted.json"), path("other/resources/new.json")), result.changedPaths)
        assertEquals(listOf("changed:app/resources/deleted.json"), result.reasons.getValue("app:jvm:test"))
        assertEquals(setOf("app:jvm:test"), selected(config, setOf("app"), "app/src").selected)
    }

    @Test fun unrelatedDeclaredPathsDoNotTriggerFullButUnknownPathsDo() {
        val config = configuration(module("app"), module("other"))
        val unrelated = selected(config, setOf("app"), "other/src/Unrelated.kt")
        assertTrue(unrelated.selected.isEmpty()); assertTrue(unrelated.fullReasons.isEmpty())
        for (changed in listOf("README.md", "app/src-other/NotSource.kt", "kvik.json", "kvik.lock.json")) {
            val result = selected(config, setOf("app", "other"), changed)
            assertEquals(setOf("unknown-path:$changed"), result.fullReasons)
            assertEquals(setOf("app:jvm:test", "other:jvm:test"), result.selected)
            assertEquals(listOf("full:unknown-path:$changed"), result.reasons.getValue("app:jvm:test"))
        }
    }

    @Test fun configurationOrToolchainFullReasonsSelectTheWholeRequestedMatrix() {
        val config = configuration(module("app"), module("other"))
        val result = selected(config, setOf("app", "other"), "app/test/Changed.kt", completed = setOf("other:jvm:test"),
            full = setOf("config-changed", "toolchain-changed"))
        assertEquals(result.requested, result.selected)
        assertEquals(setOf("config-changed", "toolchain-changed"), result.fullReasons)
        assertEquals(listOf("baseline-missing", "changed:app/test/Changed.kt", "full:config-changed", "full:toolchain-changed"), result.reasons.getValue("app:jvm:test"))
        assertEquals(listOf("full:config-changed", "full:toolchain-changed"), result.reasons.getValue("other:jvm:test"))
    }

    @Test fun nativeBuiltInTestGenerationTracksItsSourceInputsAndConfigurationChanges() {
        val app = module("app", targets = setOf("macosArm64")).let { module -> module.copy(targets = module.targets.map {
            it.copy(nativeTestSettings = NativeTestSettings("kotest", "2.3.12", Coordinate("io.kotest", "kotest-framework-symbol-processor", "6.2.5")))
        }) }
        val config = configuration(app)
        val targets = setOf("macosArm64")
        assertEquals(setOf("app:macosArm64:test"), selected(config, setOf("app"), "app/test/GeneratedSpec.kt", targets = targets).selected)
        assertEquals(setOf("app:macosArm64:test"), selected(config, setOf("app"), targets = targets, full = setOf("config-changed")).selected)
    }

    @Test fun unsupportedMatrixPairsAndMissingTestsAreNeverSilentlyDropped() {
        val config = configuration(module("app"), module("native", targets = setOf("macosArm64")))
        assertFailsWith<IllegalArgumentException> { selected(config, setOf("app", "native"), targets = setOf("jvm", "macosArm64")) }
        assertFailsWith<IllegalArgumentException> { selected(config, setOf("missing")) }
        assertFailsWith<IllegalArgumentException> { selected(config, emptySet()) }
        assertFailsWith<IllegalArgumentException> { selected(config, setOf("app"), targets = emptySet()) }
        val noTests = module("library").let { it.copy(targets = it.targets.map { target -> target.copy(test = null) }) }
        assertFailsWith<IllegalArgumentException> { selected(configuration(noTests), setOf("library")) }
        val invalid = configuration(module("app", listOf(Dependency.Module("native"))), module("native", targets = setOf("macosArm64")))
        assertFailsWith<IllegalArgumentException> { selected(invalid, setOf("app"), "app/src/Main.kt") }
    }

    @Test fun jsonSortsAllCollectionsAndDistinguishesOmissionsFromFreshExecution() {
        val config = configuration(module("z"), module("a"))
        val first = selected(config, linkedSetOf("z", "a"), "z/src/Z.kt", "z/src/A.kt")
        val second = selected(config, linkedSetOf("a", "z"), "z/src/A.kt", "z/src/Z.kt")
        assertEquals(first.toJson().canonical(), second.toJson().canonical())
        val fields = first.toJson().values
        assertEquals(setOf("schemaVersion", "requestedChecks", "selectedChecks", "omittedChecks", "changedPaths", "reasons", "fullReasons"), fields.keys)
        assertEquals(listOf("a:jvm:test"), fields.getValue("omittedChecks").arrayValue().map { it.stringValue() })
        assertEquals(listOf("z/src/A.kt", "z/src/Z.kt"), fields.getValue("changedPaths").arrayValue().map { it.stringValue() })
        assertEquals(listOf("changed:z/src/A.kt", "changed:z/src/Z.kt"), first.reasons.getValue("z:jvm:test"))
    }

    private val allKinds = setOf("build", "test", "abi-check")
    private fun withAbi(module: ModuleSpec, tests: Boolean = true): ModuleSpec = module.copy(targets = module.targets.map {
        it.copy(test = if (tests) it.test else null, abi = AbiSettings(path("api/${module.id}/${it.name}.api")))
    })
    private fun mixedChecks(modules: Set<String>, targets: Set<String> = jvm, kinds: Set<String> = allKinds) =
        modules.flatMap { module -> targets.flatMap { target -> kinds.map { "$module:$target:$it" } } }.toSet()
    private fun mixed(config: ProjectConfiguration, modules: Set<String>, vararg paths: String, targets: Set<String> = jvm,
                      kinds: Set<String> = allKinds, completed: Set<String> = mixedChecks(modules, targets, kinds), full: Set<String> = emptySet()) =
        selectAffectedChecks(config, modules, targets, kinds, paths.map(::path).toSet(), completed, full)

    @Test fun mixedChecksPropagateMainSourcesButKeepTestsOutOfMainAndAbiClosures() {
        val config = configuration(withAbi(module("library")), withAbi(module("app", listOf(Dependency.Module("library")))))
        val modules = setOf("library", "app")
        assertEquals(mixedChecks(modules), mixed(config, modules, "library/src/Api.kt").selected)
        assertEquals(mixedChecks(modules), mixed(config, modules, "library/resources/data.json").selected)
        assertEquals(setOf("library:jvm:test"), mixed(config, modules, "library/test/Test.kt").selected)
        assertEquals(setOf("app:jvm:test"), mixed(config, modules, "app/test-resources/test.json").selected)
    }

    @Test fun abiBaselineChangesSelectOnlyOwningAbiCheckAndAreKnownToTestOnlySelection() {
        val config = configuration(withAbi(module("library")), withAbi(module("app", listOf(Dependency.Module("library")))))
        val modules = setOf("library", "app")
        val selection = mixed(config, modules, "api/library/jvm.api")
        assertEquals(setOf("library:jvm:abi-check"), selection.selected)
        assertEquals(listOf("changed:api/library/jvm.api"), selection.reasons.getValue("library:jvm:abi-check"))
        assertTrue(selection.fullReasons.isEmpty())
        assertTrue(selected(config, modules, "api/library/jvm.api").selected.isEmpty())
        assertTrue(mixed(config, modules, "api/library/jvm.api", kinds = setOf("build")).selected.isEmpty())
        assertTrue(mixed(config, setOf("app"), "api/library/jvm.api").selected.isEmpty())
        val descendant = mixed(config, modules, "api/library/jvm.api/not-a-baseline")
        assertEquals(descendant.requested, descendant.selected)
        assertEquals(setOf("unknown-path:api/library/jvm.api/not-a-baseline"), descendant.fullReasons)
    }

    @Test fun abiFileDoesNotOverrideAnIndependentDeclaredResourceInput() {
        val library = withAbi(module("library")).let { module -> module.copy(targets = module.targets.map {
            it.copy(abi = AbiSettings(path("library/resources/baseline.api")))
        }) }
        val config = configuration(library, withAbi(module("app", listOf(Dependency.Module("library")))))
        val modules = setOf("library", "app")
        assertEquals(mixedChecks(modules), mixed(config, modules, "library/resources/baseline.api").selected)
    }

    @Test fun buildAndAbiChecksDoNotRequireTestDeclarations() {
        val config = configuration(withAbi(module("library"), tests = false))
        val kinds = setOf("build", "abi-check")
        assertEquals(mixedChecks(setOf("library"), kinds = kinds),
            mixed(config, setOf("library"), "library/src/Main.kt", kinds = kinds).selected)
        assertEquals(setOf("library:jvm:abi-check"),
            mixed(config, setOf("library"), "api/library/jvm.api", kinds = kinds).selected)
        assertTrue(mixed(config, setOf("library"), kinds = kinds).selected.isEmpty())
        assertFailsWith<IllegalArgumentException> { mixed(config, setOf("library"), kinds = setOf("test")) }
        val plain = configuration(module("plain").let { it.copy(targets = it.targets.map { target -> target.copy(test = null) }) })
        assertEquals(setOf("plain:jvm:build"), mixed(plain, setOf("plain"), "plain/src/Main.kt", kinds = setOf("build")).selected)
    }

    @Test fun mixedChecksPreserveTargetPartitionsAndPerTargetExactAbiInputs() {
        val library = ModuleSpec("library", listOf(
            source("common", "common"), source("jvmMain", "jvm", listOf("common")),
            source("nativeMain", "native", listOf("common")), source("jvmTest", "jvm-test"), source("nativeTest", "native-test")),
            listOf(TargetSpec("jvm", "jvmMain", "jvmTest", emptyList(), abi = AbiSettings(path("api/jvm.api"))),
                TargetSpec("macosArm64", "nativeMain", "nativeTest", emptyList(), abi = AbiSettings(path("api/native.api")))), emptyList())
        val config = configuration(library)
        val targets = setOf("jvm", "macosArm64")
        assertEquals(mixedChecks(setOf("library"), jvm), mixed(config, setOf("library"), "jvm/Main.kt", targets = targets).selected)
        assertEquals(mixedChecks(setOf("library"), targets), mixed(config, setOf("library"), "common/Main.kt", targets = targets).selected)
        assertEquals(setOf("library:macosArm64:test"), mixed(config, setOf("library"), "native-test/Test.kt", targets = targets).selected)
        assertEquals(setOf("library:macosArm64:abi-check"), mixed(config, setOf("library"), "api/native.api", targets = targets).selected)
    }

    @Test fun mixedBuildAbiAndTestClosuresIncludeRuntimeAndPluginProducersAtTheirHostTarget() {
        val runtime = module("runtime")
        val producer = module("producer", listOf(Dependency.Module("runtime", DependencyScopeKind.RUNTIME)))
        val testProducer = module("testProducer")
        val app = withAbi(module("app", plugins = listOf(
            plugin("producer", setOf("macosArm64")), plugin("testProducer", setOf("macosArm64"), scopes = setOf("test"))),
            targets = setOf("macosArm64")))
        val config = configuration(runtime, producer, testProducer, app)
        val targets = setOf("macosArm64")
        assertEquals(mixedChecks(setOf("app"), targets), mixed(config, setOf("app"), "runtime/src/Runtime.kt", targets = targets).selected)
        assertEquals(setOf("app:macosArm64:test"), mixed(config, setOf("app"), "testProducer/src/Plugin.kt", targets = targets).selected)
        assertTrue(mixed(config, setOf("app"), "producer/test/Test.kt", targets = targets).selected.isEmpty())
        val ordinary = configuration(withAbi(module("consumer", listOf(Dependency.Module("runtime", DependencyScopeKind.RUNTIME)))), runtime)
        assertEquals(mixedChecks(setOf("consumer")), mixed(ordinary, setOf("consumer"), "runtime/src/Runtime.kt").selected)
    }

    @Test fun mixedBaselineCoverageAndFullReasonsApplyToEachCheckIndependently() {
        val config = configuration(withAbi(module("app")))
        val result = mixed(config, setOf("app"), completed = setOf("app:jvm:test"))
        assertEquals(setOf("app:jvm:build", "app:jvm:abi-check"), result.selected)
        assertEquals(listOf("baseline-missing"), result.reasons.getValue("app:jvm:build"))
        assertEquals(listOf("baseline-missing"), result.reasons.getValue("app:jvm:abi-check"))
        val full = mixed(config, setOf("app"), full = setOf("config-changed"))
        assertEquals(full.requested, full.selected)
        assertTrue(full.reasons.values.all { it == listOf("full:config-changed") })
        val legacy: AffectedTestSelection = selectAffectedTests(config, setOf("app"), jvm, emptySet(), setOf("app:jvm:test"))
        val modern: AffectedCheckSelection = selectAffectedChecks(config, setOf("app"), jvm, setOf("test"), emptySet(), setOf("app:jvm:test"))
        assertEquals(legacy, modern)
    }

    @Test fun unsupportedKindsAndMissingRequestedAbiSettingsFailExplicitly() {
        val config = configuration(module("app"))
        for (kinds in listOf(emptySet(), setOf("compile"), setOf("test", "verify"), setOf("abi-check"))) {
            assertFailsWith<IllegalArgumentException> { mixed(config, setOf("app"), kinds = kinds) }
        }
        val complete = configuration(withAbi(module("app")))
        val selection = mixed(complete, setOf("app"), "app/src/Main.kt")
        val json = selection.toJson().values
        assertEquals(listOf("app:jvm:abi-check", "app:jvm:build", "app:jvm:test"),
            json.getValue("requestedChecks").arrayValue().map { it.stringValue() })
        assertEquals(setOf("schemaVersion", "requestedChecks", "selectedChecks", "omittedChecks", "changedPaths", "reasons", "fullReasons"), json.keys)
    }
}
