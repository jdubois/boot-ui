package bootuidynamiclib;

/** A library doing reflection on the application's behalf: it is the caller, the application frame is above it. */
public final class Reflector {

    private Reflector() {}

    public static Object instantiate(String name, ClassLoader loader) throws Exception {
        return Class.forName(name, true, loader).getDeclaredConstructor().newInstance();
    }

    /** {@code n} lookups of {@code name} from this library class, for the benchmark. */
    public static int lookups(String name, ClassLoader loader, int n) throws Exception {
        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc += Class.forName(name, false, loader).hashCode();
        }
        return acc;
    }
}
