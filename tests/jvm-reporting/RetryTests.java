package fixture;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

public class RetryTests {
    private static final AtomicInteger calls = new AtomicInteger();
    @Test void flaky() { if (calls.incrementAndGet() == 1) throw new AssertionError("first attempt"); }
    @Test void passing() {}
    @Test @Disabled("retained skip") void skipped() {}

    public static class AlwaysFail {
        @Test void failure() { throw new AssertionError("persistent failure"); }
    }

    public static class Disappearing {
        private static int calls;
        @TestFactory Stream<DynamicTest> tests() {
            return ++calls == 1 ? Stream.of(DynamicTest.dynamicTest("unstable", () -> { throw new AssertionError("first"); })) : Stream.empty();
        }
    }

    public static class Dynamic {
        private static int calls;
        @TestFactory Stream<DynamicTest> tests() {
            return Stream.of(DynamicTest.dynamicTest("stable", () -> { if (++calls == 1) throw new AssertionError("first"); }));
        }
    }

    public static class ContainerFailure {
        @Test void failure() { throw new AssertionError("leaf failure"); }
        @AfterAll static void teardown() { throw new IllegalStateException("teardown failed"); }
    }
}
