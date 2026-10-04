package bootuiinventoryapp;

/**
 * An application class the inventory sensor instruments in the forked-JVM tests, loaded from a jar: each method is
 * called, or not, by exactly one behavior.
 */
public class App implements Shape {

    static final long STARTED;

    static {
        STARTED = System.nanoTime();
    }

    public App() {}

    public static String beforeClaim() {
        return "before";
    }

    public static String atStartup() {
        return "startup";
    }

    public static String greet(String name) {
        return "hello " + name;
    }

    public static String neverCalled() {
        return "never";
    }

    public static String reentrant() {
        return "reentrant";
    }

    public static String calledFromCapture() {
        return "inside capture";
    }

    public static String afterRelease() {
        return "after release";
    }

    public static String $dollar() {
        return "dollar";
    }

    @Override
    public double area() {
        return 1.0;
    }
}
