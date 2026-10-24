@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.resolution

import kotlin.test.*
import kvik.core.*
import kotlinx.cinterop.*
import platform.posix.*

class JmhResolutionTest {
    @Test fun publishedJmhModelsResolveTheirReleaseClosureWithoutRepositoryInjection() {
        val coordinates = listOf(
            "org.openjdk.jmh:jmh-core:1.37", "org.openjdk.jmh:jmh-generator-reflection:1.37",
            "org.openjdk.jmh:jmh-parent:1.37", "net.sf.jopt-simple:jopt-simple:5.0.4",
            "org.sonatype.oss:oss-parent:7", "org.apache.commons:commons-math3:3.6.1",
            "org.apache.commons:commons-parent:39", "org.apache:apache:16",
        ).map(Coordinate::parse)
        val repository = Repository("central", "https://repo.maven.apache.org/maven2")
        val models = coordinates.associate { coordinate ->
            val name = "${coordinate.group}__${coordinate.name}__${coordinate.version}.pom"
            val file = fopen("${getenv("KVIK_RESOLUTION_ROOT")!!.toKString()}/tests/resolution/fixtures/jmh/$name", "rb") ?: error("Missing JMH POM fixture: $name")
            val bytes = try {
                check(fseek(file, 0, SEEK_END) == 0)
                val size = ftell(file).toInt(); rewind(file)
                ByteArray(size).also { bytes -> bytes.usePinned { check(fread(it.addressOf(0), 1u, size.toULong(), file) == size.toULong()) } }
            } finally { fclose(file) }
            "${repository.url}/${coordinate.group.replace('.', '/')}/${coordinate.name}/${coordinate.version}/${coordinate.name}-${coordinate.version}.pom" to bytes
        }
        val requested = mutableListOf<String>()
        val blobs = mutableMapOf<Digest, ByteArray>()
        val transport = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                requested += url
                assertTrue(url.startsWith(repository.url + "/"), "Published repositories must not be contacted: $url")
                val bytes = models[url] ?: if (url.endsWith(".jar")) url.encodeToByteArray() else throw MissingResource(url)
                blobs[Sha256.digest(bytes)] = bytes
                return bytes
            }
        }
        val expectedCore = setOf(coordinates[0], coordinates[3], coordinates[5])
        for (usage in listOf("java-api", "java-runtime")) {
            val selected = MavenResolver(transport, NativePomParser()).resolveAll(coordinates.take(2), jvmVariantRequest(usage, 17), listOf(repository))
            assertEquals(expectedCore, selected[0].artifacts.map { it.coordinate }.toSet())
            assertEquals(expectedCore + coordinates[1], selected[1].artifacts.map { it.coordinate }.toSet())
            assertEquals(models.keys, selected.flatMap { it.metadata }.map { it.url }.toSet())
            selected.forEach { verifyLocked(it) { digest -> blobs[digest] } }
        }
        assertTrue(requested.none { "junit/" in it || "org/ow2/asm/" in it || "snapshots" in it })
    }
}
