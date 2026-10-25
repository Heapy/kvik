package fixture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SlowTests {
    companion object { private var attempts = 0 }
    @Test @Order(1) fun flaky() {
        if (++attempts == 1) throw AssertionError("original failure before interruption")
    }
    @Test @Order(2) fun waitsForCancellation() {
        if (attempts == 1) return
        Files.writeString(Path.of("started.txt"), "started")
        Thread.sleep(30_000)
        Files.writeString(Path.of("finished.txt"), "unexpected")
    }
}

/** Deliberate process failures exercise the Native protocol boundary with real owned JVMs. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MalformedResultTests {
    @Test @Order(1) fun completed() {}
    @Test @Order(2) fun corruptsProtocolThenExits() {
        Files.writeString(Path.of("result.json"), "{truncated")
        kotlin.system.exitProcess(0)
    }
}
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NoResultTests {
    @Test @Order(1) fun completed() {}
    @Test @Order(2) fun exitsWithoutProtocol() { kotlin.system.exitProcess(0) }
}
