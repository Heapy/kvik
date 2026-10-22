package fixture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class SlowTests {
    @Test fun waitsForCancellation() {
        Files.writeString(Path.of("started.txt"), "started")
        Thread.sleep(30_000)
        Files.writeString(Path.of("finished.txt"), "unexpected")
    }
}

/** Deliberate process failures exercise the Native protocol boundary with real owned JVMs. */
class MalformedResultTests {
    @Test fun corruptsProtocolThenExits() {
        Files.writeString(Path.of("result.json"), "{truncated")
        kotlin.system.exitProcess(0)
    }
}
class NoResultTests {
    @Test fun exitsWithoutProtocol() { kotlin.system.exitProcess(0) }
}
