package bootuiinventoryapp;

/** A record: its compact canonical constructor, accessors, and generated object methods are instrumented. */
public record Point(int x, int y) {

    public Point {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("negative");
        }
    }

    public int sum() {
        return x + y;
    }
}
