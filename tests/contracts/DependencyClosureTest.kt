package kvik.core

import kotlin.test.*

class DependencyClosureTest {
    private fun external(name: String, exported: Boolean = false, runtime: Boolean = false) = Dependency.External(Coordinate("g", name, "1"), if(runtime) DependencyScopeKind.RUNTIME else DependencyScopeKind.COMPILE, exported)
    private fun module(id: String, main: List<Dependency>, test: List<Dependency> = emptyList()) = ModuleSpec(id, listOf(
        SourceSet("main", emptyList(), emptyList(), emptyList(), main), SourceSet("test", emptyList(), emptyList(), listOf("main"), test)),
        listOf(TargetSpec("jvm", "main", "test", emptyList())), emptyList())

    @Test fun exportedChainsPrivateApisAndRuntimeOnlyModuleEdgesHaveSeparateReachability() {
        val leaf = module("leaf", listOf(external("leafPrivate"), external("leafPublic", exported = true)))
        val hidden = module("hidden", listOf(external("hiddenApi", exported = true)))
        val service = module("service", listOf(external("public", exported = true), external("private"),
            external("runtime", runtime = true), Dependency.Module("leaf", exported = true), Dependency.Module("hidden", DependencyScopeKind.RUNTIME)))
        val app = module("app", listOf(Dependency.Module("service")), listOf(external("testRuntime", runtime = true)))
        val project = ProjectSpec("sample", emptyList(), listOf(app, service, leaf, hidden))
        fun closure(id: String, scope: String, usage: DependencyUsage) = dependencyClosure(project, id, "jvm", scope, usage)
        assertEquals(setOf("public", "private", "leafPublic"), closure("service", "main", DependencyUsage.COMPILE).external.map { it.name }.toSet())
        val compile = closure("app", "test", DependencyUsage.COMPILE)
        assertEquals(listOf("service", "leaf"), compile.modules)
        assertEquals(setOf("public", "leafPublic"), compile.external.map { it.name }.toSet())
        val runtime = closure("app", "test", DependencyUsage.RUNTIME)
        assertEquals(listOf("service", "leaf", "hidden"), runtime.modules)
        assertEquals(setOf("public", "private", "runtime", "leafPrivate", "leafPublic", "hiddenApi", "testRuntime"), runtime.external.map { it.name }.toSet())
        assertFails { Dependency.Module("hidden", DependencyScopeKind.RUNTIME, exported = true) }
    }
}
