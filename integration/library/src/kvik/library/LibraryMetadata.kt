package kvik.library

import kvik.core.*

internal fun LibraryPackage.basename() = "${coordinate.name}-${coordinate.version}"
internal fun LibraryPackage.directory() = "library/${coordinate.group.replace('.', '/')}/${coordinate.name}/${coordinate.version}"
internal fun LibraryPackage.extension() = if (target == "jvm") "jar" else "klib"

internal fun LibraryPackage.pom(): ByteArray = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n")
    append("  <!-- do_not_remove: published-with-gradle-metadata -->\n")
    append("  <modelVersion>4.0.0</modelVersion>\n")
    append("  <groupId>${coordinate.group}</groupId><artifactId>${coordinate.name}</artifactId><version>${coordinate.version}</version>\n")
    append("  <packaging>${extension()}</packaging>\n  <dependencies>\n")
    dependencies.sortedBy { it.coordinate.toString() }.forEach { dependency ->
        val value = dependency.pomCoordinate ?: dependency.coordinate
        append("    <dependency><groupId>${value.group}</groupId><artifactId>${value.name}</artifactId><version>${value.version}</version><scope>${if (dependency.api) "compile" else "runtime"}</scope></dependency>\n")
    }
    append("  </dependencies>\n</project>\n")
}.encodeToByteArray().also { require(it.size <= LIBRARY_METADATA_LIMIT) { "Library POM exceeds 1 MiB" } }

internal fun LibraryPackage.moduleMetadata(artifact: Digest): ByteArray {
    val name = "${basename()}.${extension()}"
    fun variant(variantName: String, usage: String, selected: List<LibraryDependency>): JsonValue {
        val attributes = linkedMapOf<String, JsonValue>("org.gradle.category" to "library".json(),
            "org.gradle.usage" to usage.json(), "org.jetbrains.kotlin.platform.type" to (if (target == "jvm") "jvm" else "native").json())
        if (target == "jvm") {
            attributes["org.gradle.libraryelements"] = "jar".json()
            attributes["org.gradle.dependency.bundling"] = "external".json()
            attributes[JVM_TARGET_VERSION_ATTRIBUTE] = checkNotNull(jvmTarget).json()
        } else {
            attributes["org.jetbrains.kotlin.native.target"] = NativeTarget.require(target).compilerName.json()
            attributes["artifactType"] = "org.jetbrains.kotlin.klib".json()
        }
        return obj("name" to variantName.json(), "attributes" to JsonValue.Object(attributes),
            "dependencies" to selected.sortedBy { it.coordinate.toString() }.map {
                obj("group" to it.coordinate.group.json(), "module" to it.coordinate.name.json(),
                    "version" to obj("requires" to it.coordinate.version.json()))
            }.json(), "files" to listOf(obj("name" to name.json(), "url" to name.json(),
                "size" to artifact.sizeBytes.json(), "sha256" to artifact.hash.json())).json())
    }
    // KGP/KTC associate resolved variant names with PSM source sets; usage strings are not variant names.
    val variants = if (target == "jvm") listOf(variant("jvmApiElements", "java-api", dependencies.filter { it.api }),
        variant("jvmRuntimeElements", "java-runtime", dependencies))
        else listOf(variant("${target}ApiElements", "kotlin-api", dependencies.filter { it.api }),
            variant("${target}RuntimeElements", "kotlin-runtime", dependencies))
    val owner = rootCoordinate ?: coordinate
    val component = obj("group" to owner.group.json(), "module" to owner.name.json(), "version" to owner.version.json()).let { value ->
        if (rootCoordinate == null) value else JsonValue.Object(value.values +
            ("url" to "../../${owner.name}/${owner.version}/${owner.name}-${owner.version}.module".json()))
    }
    // Gradle's streaming reader requires formatVersion first; kvik-json's alphabetical root order is not this protocol.
    return ("{\"formatVersion\":\"1.1\",\"component\":" + component.canonical() + ",\"variants\":" + variants.json().canonical() + "}")
        .encodeToByteArray().also { require(it.size <= LIBRARY_METADATA_LIMIT) { "Library module metadata exceeds 1 MiB" } }
}
