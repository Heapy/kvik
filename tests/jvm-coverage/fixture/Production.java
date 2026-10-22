package fixture;

public final class Production {
    public static int choose(boolean selected) {
        if (selected) {
            return 7;
        }
        return 9;
    }

    public static int neverCalled() {
        return 42;
    }
}
