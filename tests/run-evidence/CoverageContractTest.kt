package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class CoverageContractTest {
    private val digest = Sha256.digest("source".encodeToByteArray())
    private val source = CoverageSource("library", "commonMain", LogicalPath("src/Main.kt"), digest)
    private val spec = JvmCoverageSpec(JACOCO_VERSION, digest, digest, listOf(CoverageClassRoot("library", LogicalPath("modules/library/jvm/main"))), listOf(source))

    @Test fun instrumentationAndSourceOwnershipSurviveRoundTrip() {
        assertEquals(spec, JvmCoverageSpec.fromJson(parseJson(spec.toJson().canonical())))
        assertEquals("src/Main.kt", source.path.value)
        assertEquals("coverage-sources/src/Main.kt", source.inputPath.value)
        assertNotEquals(Sha256.digest(spec.toJson()), Sha256.digest(spec.copy(agent = digest.copy(sizeBytes = digest.sizeBytes + 1)).toJson()))
        assertNotEquals(Sha256.digest(spec.toJson()), Sha256.digest(spec.copy(excludes = listOf("example.Generated*")).toJson()))
    }

    @Test fun unsupportedEnginesAmbiguousOwnershipAndInjectedOptionsAreRejected() {
        assertFails { spec.copy(version = "unverified") }
        assertFails { spec.copy(sources = listOf(source, source.copy(sourceSet = "jvmMain"))) }
        assertFails { spec.copy(excludes = listOf("a,output=tcpserver")) }
        assertFails { spec.copy(excludes = listOf("a:b")) }
        assertFails { spec.copy(sources = listOf(source.copy(module = "unknown"))) }
        assertFails { spec.copy(classDirectories = spec.classDirectories + spec.classDirectories) }
    }

    @Test fun unknownUncoveredAndNonExecutableLinesRemainDistinct() {
        val lines = listOf(LineCoverageState.COVERED, LineCoverageState.UNCOVERED, LineCoverageState.NON_EXECUTABLE, LineCoverageState.UNKNOWN)
            .mapIndexed { index, state -> CoverageLine(index + 1, state) }
        val file = SourceCoverage(source, 4, lines, false, listOf("Partial producer mapping"))
        assertEquals(1, file.covered)
        assertEquals(2, file.executable)
        assertEquals(file, SourceCoverage.fromJson(file.toJson()))
        assertFails { file.copy(complete = true) }
        assertFails { file.copy(lines = lines.dropLast(1)) }
        assertFails { file.copy(lines = listOf(lines[0], lines[0], lines[2], lines[3])) }
        assertEquals(0, SourceCoverage(source, 0, emptyList(), true).executable)
    }
}
