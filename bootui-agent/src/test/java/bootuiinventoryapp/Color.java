package bootuiinventoryapp;

/** An enum: its constructor, {@code values()}, and {@code valueOf} are instrumented, its synthetic {@code $values} not. */
public enum Color {
    RED,
    GREEN;

    public String label() {
        return name().toLowerCase();
    }
}
