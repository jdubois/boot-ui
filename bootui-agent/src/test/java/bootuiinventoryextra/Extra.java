package bootuiinventoryextra;

/**
 * Outside the inventory tests' claimed package until a refine adds it, and loaded again in fresh class loaders: each
 * method is called by exactly one behavior.
 */
public final class Extra {

    private Extra() {}

    public static String run() {
        return "extra";
    }

    public static String second() {
        return "second";
    }

    public static String afterRelease() {
        return "extra after release";
    }
}
