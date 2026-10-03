package bootuiagentit.run;

/** Loaded by each simulated run's own class loader, and advised by the probe. */
public final class Probed {

    private Probed() {}

    public static int touch(int value) {
        return value + 1;
    }
}
