package bootuiinventoryapp;

/** In the claimed package, but left out of the application jar: loaded from the test root, so never instrumented. */
public final class TestRootOnly {

    private TestRootOnly() {}

    public static String run() {
        return "test root";
    }
}
