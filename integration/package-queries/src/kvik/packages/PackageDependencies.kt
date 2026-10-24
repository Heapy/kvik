package kvik.packages

import kvik.core.*
import kvik.resolution.*

fun queryPackageDependencies(project: String, coordinate: Coordinate, variant: VariantRequest, repositories: List<Repository>,
                             pins: List<Coordinate>, transport: QueryTransport, parser: PomParser): JsonValue.Object {
    require(coordinate.toString().length <= 1024 && pins.size <= 256 && pins.all { it.toString().length <= 1024 }) { "Package query coordinate/pin bound exceeded" }
    require(variant.platform == "jvm" || NativeTarget.find(variant.platform) != null || KotlinJsTarget.find(variant.platform) != null) { "Unsupported query target" }
    require(variant.usage in if (variant.platform == "jvm") setOf("java-api", "java-runtime")
        else if (KotlinJsTarget.find(variant.platform) != null) setOf("kotlin-api", "kotlin-runtime") else setOf("kotlin-api")) { "Unsupported target/usage" }
    val query = obj("project" to project.json(), "coordinate" to coordinate.toString().json(),
        "repositories" to repositories.map { obj("id" to it.id.json(), "url" to it.url.json()) }.json(),
        "variant" to obj("platform" to variant.platform.json(), "usage" to variant.usage.json(), "attributes" to JsonValue.Object(variant.attributes.mapValues { it.value.json() })),
        "pins" to pins.map { it.toString().json() }.json(), "mode" to transport.mode.wire.json())
    val diagnostics = mutableListOf<QueryDiagnostic>()
    var status = "complete"
    var truncated = false
    val graph = try {
        MavenResolver(transport, parser, pins).resolveGraph(listOf(coordinate), variant, repositories).toJson().also {
            truncated = it.values.getValue("truncated").booleanValue()
            if (truncated) {
                status = "incomplete"
                diagnostics += QueryDiagnostic("KVIK_QUERY_TRUNCATED", "Shortest-path output exceeded the path bound; graph edges remain available")
            }
        }
    } catch (failure: QueryUnavailable) {
        status = "incomplete"; diagnostics += QueryDiagnostic(failure.code, failure.message.orEmpty()); JsonValue.Null
    } catch (failure: MissingResource) {
        status = "incomplete"; diagnostics += QueryDiagnostic("KVIK_PACKAGE_NOT_FOUND", failure.message.orEmpty(), failure.url); JsonValue.Null
    } catch (failure: Exception) {
        val message = failure.message ?: "Unsupported dependency metadata"
        val unavailable = message.startsWith("Missing metadata") || message.startsWith("Missing parent POM") || message.startsWith("Artifact digest mismatch")
        val bound = message.contains("bound", ignoreCase = true) || message.contains("exceeds", ignoreCase = true)
        status = if (unavailable || bound) "incomplete" else "unsupported"
        truncated = bound
        diagnostics += QueryDiagnostic(if (bound) "KVIK_QUERY_BOUND" else if (unavailable) "KVIK_PACKAGE_UNAVAILABLE" else "KVIK_PACKAGE_UNSUPPORTED", message)
        JsonValue.Null
    }
    return JsonValue.Object(envelope("package-dependencies", query, transport.now, transport.sources, diagnostics,
        status = status, truncated = truncated) + ("graph" to graph))
}
