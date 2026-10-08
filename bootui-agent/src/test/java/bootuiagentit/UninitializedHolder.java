package bootuiagentit;

/**
 * A holder the thread-locals sensor's resolver must never initialize (PLAN-v2 §5.16, M5-5f): loaded, never initialized,
 * its static initializer records that it ran.
 */
public final class UninitializedHolder {

    static final ThreadLocal<String> LOCAL = new ThreadLocal<>();

    static {
        System.setProperty("bootui.it.clinit-ran", "true");
    }

    private UninitializedHolder() {}
}
