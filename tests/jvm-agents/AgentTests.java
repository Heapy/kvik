package fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class AgentTests {
    @Test public void agentRunsInsideOrdinaryTestProcess() throws Exception {
        assertEquals("agent token", System.getProperty("kvik.agent"));
        System.setProperty("kvik.testRan", "true");
        if (System.getProperty("kvik.agent.mode").equals("slow")) {
            Files.writeString(Path.of("test-started"), "started");
            Thread.sleep(30_000);
        }
    }
}
