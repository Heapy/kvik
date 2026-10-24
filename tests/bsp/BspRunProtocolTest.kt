package kvik.bsp

import kvik.core.*
import kotlin.test.*

class BspRunProtocolTest {
    private val configuration = parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "runs".json(),
        "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
        "modules" to listOf("app", "library").map { module ->
            obj("id" to module.json(), "compilerPlugins" to emptyList<JsonValue>().json(),
                "sourceSets" to listOf("main", "tests").map { name -> obj("name" to name.json(),
                    "directories" to listOf("$module/$name".json()).json(), "resources" to emptyList<JsonValue>().json(),
                    "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to emptyList<JsonValue>().json()) }.json(),
                "targets" to listOf("jvm", "macosArm64", "linuxX64", "js").map { target -> JsonValue.Object(buildMap {
                    put("name", target.json()); put("main", "main".json()); put("test", "tests".json()); put("compilerOptions", emptyList<JsonValue>().json())
                    if (module == "app" && target != "js") put("application", ApplicationSpec("example.Main", listOf("default"), mapOf("DECLARED" to "yes")).toJson())
                }) }.json())
        }.json()))
    private val digest = Digest("a".repeat(64), 1)
    private val model = BspProjectModel(ProjectInspection(configuration, digest, digest, emptyList(), emptyMap(), emptyList()), "/project", LogicalPath("kvik.json"))
    private fun target(module: String = "app", target: String = "jvm", scope: String = "main") = obj("uri" to "file:///project/kvik.json#$module/$target/$scope".json())
    private class Operations : BspOperations {
        override var activeId: JsonValue? = null
        override val compileTargets = setOf("jvm", "macosArm64", "linuxX64", "js")
        override val testTargets = setOf("jvm", "macosArm64")
        override val runTargets = setOf("jvm", "macosArm64")
        var request: BspBuildRequest? = null
        var cancelled: JsonValue? = null
        override fun start(request: BspBuildRequest, model: BspProjectModel) { this.request = request; activeId = request.id }
        override fun cancel(id: JsonValue) { cancelled = id }
    }
    private fun send(session: BspSession, method: String, params: JsonValue = obj(), id: Int? = 1): JsonValue.Object? =
        session.receive(JsonValue.Object(buildMap {
            put("jsonrpc", "2.0".json()); put("method", method.json()); put("params", params)
            id?.let { put("id", it.json()) }
        }).canonical().encodeToByteArray())
    private fun session(operations: Operations? = Operations()): BspSession = BspSession(operations) { model }.also {
        val initialized = send(it, "build/initialize", obj("displayName" to "test".json(), "version" to "1".json(),
            "bspVersion" to BSP_VERSION.json(), "rootUri" to "file:///project/".json(), "capabilities" to obj("languageIds" to listOf("kotlin".json()).json())))!!
        val capabilities = initialized.values.getValue("result").objectValue().getValue("capabilities").objectValue()
        assertEquals(operations != null, "runProvider" in capabilities)
        send(it, "build/initialized", id = null)
    }
    private fun error(value: JsonValue.Object?, code: Long = -32602) {
        assertEquals(code, assertNotNull(value).values.getValue("error").objectValue().getValue("code").longValue())
    }

    @Test fun onlyConfiguredHostMainApplicationsAdvertiseRun() {
        val operations = Operations()
        val result = send(session(operations), "workspace/buildTargets")!!.values.getValue("result").objectValue().getValue("targets").arrayValue()
        val runnable = result.filter { it.objectValue().getValue("capabilities").objectValue().getValue("canRun").booleanValue() }
        assertEquals(setOf(target(), target(target = "macosArm64")), runnable.map { it.objectValue().getValue("id") }.toSet())
        assertTrue(model.targets().values.getValue("targets").arrayValue().none { it.objectValue().getValue("capabilities").objectValue().getValue("canRun").booleanValue() })
        error(send(session(null), "buildTarget/run", obj("target" to target())), -32601)
    }

    @Test fun runKeepsDefaultArgumentsDistinctFromExplicitEmptyAndDecodesLocalDirectory() {
        val operations = Operations(); val session = session(operations)
        assertNull(send(session, "buildTarget/run", obj("target" to target()), id = 2))
        assertNull(operations.request!!.originId)
        assertEquals(BspRunOptions(), operations.request!!.runOptions)
        operations.activeId = null
        val params = obj("target" to target(), "originId" to "request-1".json(), "arguments" to emptyList<JsonValue>().json(),
            "environmentVariables" to obj("DECLARED" to "override".json()), "workingDirectory" to "file://localhost/tmp/caf%C3%A9%20%23%25/".json())
        assertNull(send(session, "buildTarget/run", params, id = 3))
        assertEquals("request-1", operations.request!!.originId)
        assertEquals(BspRunOptions(emptyList(), mapOf("DECLARED" to "override"), "/tmp/café #%/"), operations.request!!.runOptions)
        val request = operations.request!!
        assertEquals("buildTarget/run", request.method)
        assertEquals(1, request.targets.size)
        assertEquals("main", request.targets.single().compilation)
        assertEquals(request.runOptions, BspRunOptions.fromJson(request.runOptions!!.toJson()))
    }

    @Test fun invalidScopesAndLaunchParametersNeverStartAnOperation() {
        val operations = Operations(); val session = session(operations)
        for (target in listOf(target("library"), target(scope = "test"), target(target = "linuxX64"), target(target = "js"), obj("uri" to "missing".json()))) {
            error(send(session, "buildTarget/run", obj("target" to target)))
        }
        for ((field, value) in listOf("arguments" to listOf("bad\u0000".json()).json(),
            "arguments" to listOf(true.json()).json(), "environmentVariables" to obj("KVIK_APPLICATION_INPUTS" to "/tmp".json()),
            "environmentVariables" to obj("BAD=KEY" to "value".json()), "environmentVariables" to obj("VALUE" to false.json()),
            "workingDirectory" to "https://example.com".json(), "workingDirectory" to "file://remote/tmp".json(),
            "workingDirectory" to "file:///tmp/%FF".json(), "workingDirectory" to "file:///tmp/%00".json(),
            "workingDirectory" to "file:///tmp?a".json(), "dataKind" to "scala-main-class".json(), "data" to obj())) {
            error(send(session, "buildTarget/run", obj("target" to target(), field to value)))
        }
        assertNull(operations.request)
        assertFails { BspRunOptions(workingDirectory = "relative") }
        assertFails { BspRunOptions(arguments = List(257) { "arg" }) }
    }

    @Test fun activeRunKeepsQueriesResponsiveAndParticipatesInCancellation() {
        val operations = Operations(); val session = session(operations)
        assertNull(send(session, "buildTarget/run", obj("target" to target()), id = 2))
        assertNotNull(send(session, "workspace/buildTargets", id = 3)?.values?.get("result"))
        error(send(session, "workspace/reload", id = 4), -32603)
        error(send(session, "buildTarget/run", obj("target" to target()), id = 5))
        send(session, "$/cancelRequest", obj("id" to 2.json()), id = null)
        assertEquals(2.json(), operations.cancelled)
        operations.cancelled = null
        send(session, "build/shutdown", id = 6)
        assertEquals(2.json(), operations.cancelled)
    }
}
