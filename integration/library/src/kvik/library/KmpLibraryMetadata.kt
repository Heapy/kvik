package kvik.library

import kvik.core.*

internal fun KmpLibraryPackage.basename() = "${coordinate.name}-${coordinate.version}"
internal fun KmpLibraryPackage.directory() = "library/${coordinate.group.replace('.', '/')}/${coordinate.name}/${coordinate.version}"
private fun KmpLibraryPackage.sharedDependencies() = sourceSets.flatMap { it.dependencies }.distinct().sortedBy { it.toString() }
internal fun LibraryPackage.rootVariants(): List<Pair<String, String>> = if (target == "jvm")
    listOf("jvmApiElements" to "java-api", "jvmRuntimeElements" to "java-runtime")
    else listOf("${target}ApiElements" to "kotlin-api")

internal fun KmpLibraryPackage.projectStructure(): ByteArray = obj("projectStructure" to obj(
    "formatVersion" to "0.3.3".json(), "isPublishedAsRoot" to "true".json(),
    "variants" to targets.sortedBy { it.target }.flatMap { target -> target.rootVariants().map { (name, _) ->
        obj("name" to name.json(), "sourceSet" to sourceSetsByTarget.getValue(target.target).sorted().map { it.json() }.json())
    } }.json(),
    "sourceSets" to sourceSets.sortedBy { it.name }.map { source -> obj("name" to source.name.json(),
        "dependsOn" to source.dependsOn.sorted().map { it.json() }.json(), "binaryLayout" to "klib".json(),
        "moduleDependency" to source.dependencies.sortedBy { it.toString() }.map { "${it.group}:${it.name}".json() }.json())
    }.json())).canonical().encodeToByteArray().also { require(it.size <= LIBRARY_METADATA_LIMIT) { "Project structure metadata exceeds 1 MiB" } }

internal fun KmpLibraryPackage.pom(): ByteArray = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n")
    append("  <!-- do_not_remove: published-with-gradle-metadata -->\n  <modelVersion>4.0.0</modelVersion>\n")
    append("  <groupId>${coordinate.group}</groupId><artifactId>${coordinate.name}</artifactId><version>${coordinate.version}</version>\n")
    append("  <packaging>jar</packaging>\n  <dependencies>\n")
    sharedDependencies().forEach { value ->
        append("    <dependency><groupId>${value.group}</groupId><artifactId>${value.name}</artifactId><version>${value.version}</version><scope>compile</scope></dependency>\n")
    }
    append("  </dependencies>\n</project>\n")
}.encodeToByteArray().also { require(it.size <= LIBRARY_METADATA_LIMIT) }

internal fun KmpLibraryPackage.moduleMetadata(artifact: Digest, inputs: TreeManifest): ByteArray {
    val name = "${basename()}.jar"
    val metadata = obj("name" to "metadataApiElements".json(), "attributes" to obj("org.gradle.category" to "library".json(),
        "org.gradle.usage" to "kotlin-metadata".json(), "org.jetbrains.kotlin.platform.type" to "common".json()),
        "dependencies" to sharedDependencies().map { obj("group" to it.group.json(), "module" to it.name.json(),
            "version" to obj("requires" to it.version.json())) }.json(),
        "files" to listOf(obj("name" to name.json(), "url" to name.json(), "size" to artifact.sizeBytes.json(), "sha256" to artifact.hash.json())).json())
    val redirects = targets.sortedBy { it.target }.flatMap { target ->
        val targetArtifact = inputs.files.single { it.path.value == "${target.directory()}/${target.basename()}.${target.extension()}" }
        val targetVariants = parseJson(target.moduleMetadata(targetArtifact.digest).decodeToString()).objectValue().getValue("variants").arrayValue()
        target.rootVariants().map { (variantName, usage) ->
            val targetVariant = targetVariants.single {
                it.objectValue().getValue("attributes").objectValue().getValue("org.gradle.usage").stringValue() == usage
            }.objectValue()
            obj("name" to variantName.json(), "attributes" to targetVariant.getValue("attributes"),
                "available-at" to obj("url" to relativeUrl(directory(), "${target.directory()}/${target.basename()}.module").json(),
                    "group" to target.coordinate.group.json(), "module" to target.coordinate.name.json(), "version" to target.coordinate.version.json()))
        }
    }
    val component = obj("group" to coordinate.group.json(), "module" to coordinate.name.json(), "version" to coordinate.version.json())
    // Gradle requires formatVersion first; nested values still use deterministic canonical encoding.
    return ("{\"formatVersion\":\"1.1\",\"component\":" + component.canonical() + ",\"variants\":" + (listOf(metadata) + redirects).json().canonical() + "}")
        .encodeToByteArray().also { require(it.size <= LIBRARY_METADATA_LIMIT) }
}

private fun relativeUrl(directory: String, destination: String): String {
    val from = directory.split('/'); val to = destination.split('/')
    var common = 0
    while (common < from.size && common < to.size && from[common] == to[common]) common++
    return (List(from.size - common) { ".." } + to.drop(common)).joinToString("/")
}
