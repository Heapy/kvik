package fixture;

import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InterruptedTests {
    @Test @Order(1) void completed() {}
    @Test @Order(2) void exits() { Runtime.getRuntime().halt(7); }

    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    public static class Retry {
        private static int attempts;
        @Test @Order(1) void flaky() {
            if (++attempts == 1) throw new AssertionError("first attempt");
        }
        @Test @Order(2) void exitsDuringRetry() {
            if (attempts == 2) Runtime.getRuntime().halt(7);
        }
    }
}
