package fixture;

public final class Shared {
    public static int covered() { return 3; }
    public static final class Included { public static int value() { return 11; } } public static final class Excluded { public static int value() { return 22; } }
    public static final class ExcludedOnly {
        public static int value() { return 33; }
    }
}
