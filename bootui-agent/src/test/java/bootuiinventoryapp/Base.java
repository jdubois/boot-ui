package bootuiinventoryapp;

/** A superclass whose constructor takes an argument, called by {@link Derived}'s through {@code super(...)}. */
public class Base {

    private final String name;

    public Base(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
