package kvik.jvm

import kvik.core.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipFile

/** Prepares declared metadata containers, then runs the pinned CLI in this same owned JVM. */
object DocumentationInputs {
    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size == 2) { "DocumentationInputs <workspace> <request>" }
        val workspace = Workspace(Path.of(arguments[0]))
        val request = workspace.readJson(arguments[1]).fields(setOf("specification", "configuration", "distribution", "inputs"))
        val spec = DocumentationSpec.fromJson(request.getValue("specification"))
        val distribution = DocumentationDistribution.fromJson(request.getValue("distribution"))
        val inputs = TreeManifest.fromJson(request.getValue("inputs"))
        require(inputs.files.size in 1..100_000 && inputs.files.none { it.executable } && inputs.files.all { it.digest.sizeBytes <= 128L * 1024 * 1024 } &&
            inputs.files.sumOf { it.digest.sizeBytes } <= 512L * 1024 * 1024) { "Invalid documentation input bounds" }
        for (entry in inputs.files) {
            val path = workspace.path("input/${entry.path.value}")
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == entry.digest.sizeBytes &&
                Sha256.digest(Files.readAllBytes(path)) == entry.digest) { "Documentation input identity differs: ${entry.path.value}" }
        }
        val jars = distribution.artifacts.files.map { entry ->
            val path = workspace.path("tools/${entry.path.value}")
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == entry.digest.sizeBytes &&
                Sha256.digest(Files.readAllBytes(path)) == entry.digest) { "Documentation distribution changed" }
            path
        }
        val original = request.getValue("configuration").objectValue()
        val configuredSets = original.getValue("sourceSets").arrayValue()
        require(configuredSets.size == spec.sourceSets.size)
        val scratch = Files.createTempDirectory(workspace.root, ".documentation-metadata-")
        try {
            val sets = spec.sourceSets.mapIndexed { index, source ->
                val fields = configuredSets[index].objectValue()
                val classpath = source.classpath.flatMapIndexed { pathIndex, logical ->
                    val path = workspace.path("input/${logical.value}")
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS)) { "Invalid documentation classpath" }
                    val metadataJar = logical.value.endsWith(".jar") && ZipFile(path.toFile()).use {
                        it.getEntry("META-INF/kotlin-project-structure-metadata.json") != null
                    }
                    if (source.platform == "common" || metadataJar) metadataClasspath(workspace, listOf("input/${logical.value}"),
                        scratch.resolve("$index/$pathIndex"), source.supportingTargets, minimumTargets = 1)
                    else if (Files.isDirectory(path, NOFOLLOW_LINKS)) listOf(workspace.classpath("input/${logical.value}"))
                    else {
                        require(logical.value.endsWith(".jar") || source.platform == "native" && logical.value.endsWith(".klib")) { "Unsupported documentation classpath file" }
                        listOf(path)
                    }
                }
                JsonValue.Object(fields + ("classpath" to classpath.map { it.toString().json() }.json()))
            }
            val config = JsonValue.Object(original + ("sourceSets" to sets.json()))
            workspace.publish("dokka.json", config)
            val cli = workspace.path("tools/${distribution.cliPath.value}")
            val main = JarFile(cli.toFile()).use { it.manifest.mainAttributes.getValue("Main-Class") }
            require(!main.isNullOrBlank()) { "Dokka CLI has no main entry point" }
            URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val old = Thread.currentThread().contextClassLoader
                try {
                    Thread.currentThread().contextClassLoader = loader
                    val entry = loader.loadClass(main)
                    require(entry.classLoader === loader && Path.of(entry.protectionDomain.codeSource.location.toURI()).toRealPath() == cli.toRealPath()) { "Dokka entry escaped its pinned CLI" }
                    entry.getMethod("main", Array<String>::class.java).invoke(null, arrayOf(workspace.path("dokka.json").toString()))
                } finally { Thread.currentThread().contextClassLoader = old }
            }
        } finally { workspace.removeTree(scratch) }
    }
}
