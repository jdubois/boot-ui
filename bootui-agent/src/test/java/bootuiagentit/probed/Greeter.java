package bootuiagentit.probed;

/** A class the diagnostic probe advises in the forked-JVM tests. */
public final class Greeter {

    private Greeter() {}

    public static String greet(String name) {
        return "hello " + name;
    }
}
