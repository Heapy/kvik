package fixture;

import org.junit.jupiter.api.*;
import java.util.stream.Stream;

public class AbortedSuite {
    @BeforeAll static void before() { Assumptions.assumeTrue(false, "container assumption"); }
    @Test void unstarted() { throw new AssertionError("must not execute"); }

    public static class AfterAllAbort {
        @Test void passed() {}
        @AfterAll static void after() { Assumptions.assumeTrue(false, "teardown aborted"); }
    }

    public static class DynamicAbort {
        @TestFactory Stream<DynamicTest> tests() {
            return Stream.concat(Stream.of(DynamicTest.dynamicTest("first passed", () -> {})),
                Stream.generate(() -> {
                    Assumptions.assumeTrue(false, "dynamic discovery aborted");
                    throw new AssertionError("unreachable");
                }));
        }
    }

    public static class EmptyFactoryAbort {
        @Test void passed() {}
        @TestFactory Stream<DynamicTest> tests() {
            Assumptions.assumeTrue(false, "factory aborted before discovery");
            throw new AssertionError("unreachable");
        }
    }

    public static class FailureAndAbort {
        @Test void failed() { throw new AssertionError("leaf failure"); }
        @AfterAll static void after() { Assumptions.assumeTrue(false, "teardown aborted"); }
    }

    public static class LeafAssumption {
        @Test void passed() {}
        @Test void skipped() { Assumptions.assumeTrue(false, "leaf assumption"); }
    }
}
