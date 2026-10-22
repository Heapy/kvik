package fixture;

public final class Main {
    public static void main(String[] args) {
        if (Production.choose(true) != 7) throw new AssertionError("fixture");
    }
}
