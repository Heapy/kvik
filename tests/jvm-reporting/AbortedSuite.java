package fixture;

import org.junit.jupiter.api.*;

public class AbortedSuite {
    @BeforeAll static void before() { Assumptions.assumeTrue(false, "container assumption"); }
    @Test void unstarted() { throw new AssertionError("must not execute"); }
}
