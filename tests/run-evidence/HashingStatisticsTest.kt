package kvik.runevidence

import kvik.core.*
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.nanoseconds

class HashingStatisticsTest {
    @Test fun hashingIsTransparentAndCountsRepeatedBytesWithAnIndependentClock() {
        val clock = TestTimeSource()
        val measured = MeasuredHasher(Hasher { bytes ->
            clock += 7.nanoseconds
            Sha256.digest(bytes)
        }, clock)
        val bytes = byteArrayOf(1, 2, 3)
        assertEquals(Sha256.digest(bytes), measured.digest(bytes))
        val first = measured.snapshot()
        clock += 100.nanoseconds // Reads/encoding outside the hasher must not inflate hash time.
        assertEquals(Sha256.digest(bytes), measured.digest(bytes))
        assertEquals(Sha256.digest(byteArrayOf()), measured.digest(byteArrayOf()))
        assertEquals(HashingStatistics(1, 3, 7), first)
        assertEquals(HashingStatistics(3, 6, 21), measured.snapshot())
        assertEquals(measured.snapshot(), HashingStatistics.fromJson(measured.snapshot().toJson()))
    }

    @Test fun impossibleStatisticsAreRejected() {
        assertFails { HashingStatistics(-1, 0, 0) }
        assertFails { HashingStatistics(0, 1, 0) }
        assertFails { HashingStatistics(0, 0, 1) }
        assertFails { InputCaptureStatistics("unknown", 1, HashingStatistics(0, 0, 0)) }
        assertFails { InputCaptureStatistics("filesystem", 6, HashingStatistics(1, 3, 7)) }
        val value = InputCaptureStatistics("source-provider", 10, HashingStatistics(1, 3, 7))
        assertEquals(value, InputCaptureStatistics.fromJson(value.toJson()))
    }
}
