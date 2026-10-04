package bootuiinventoryapp;

/** Constructors that delegate: one to {@code super(args)} with a computed argument, one to {@code this(...)}. */
public class Derived extends Base {

    private final int length;

    public Derived(String name) {
        super(name.toUpperCase());
        this.length = name.length();
    }

    public Derived() {
        this("default");
    }

    public int length() {
        return length;
    }
}
