package fixture;

public final class Main {
    public static void main(String[] args) {
        if (Production.choose(true) != 7) throw new AssertionError("fixture");
        if (Shared.covered() != 3 || Shared.Excluded.value() != 22 || Shared.ExcludedOnly.value() != 33)
            throw new AssertionError("shared source fixture");
    }
}
