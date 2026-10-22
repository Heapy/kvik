package fixture;

import org.junit.jupiter.api.*;

@DisplayName("Detailed suite")
public class DetailsTests {
    @Test @DisplayName("Visible passing name") void passing() throws Exception { Thread.sleep(1); }
    @Test void assertion() { throw new AssertionError("expected assertion"); }
    @Test void exception() { throw new IllegalStateException("outer failure", new IllegalArgumentException("nested cause")); }
    @Test @Disabled("explicit disabled reason") void disabled() { throw new AssertionError("must not execute"); }
    @Test void assumption() { Assumptions.assumeTrue(false, "explicit assumption reason"); }
    @Test void boundedUnicode() { throw new AssertionError("x".repeat(8191) + "🚀" + "tail".repeat(3000)); }
}
