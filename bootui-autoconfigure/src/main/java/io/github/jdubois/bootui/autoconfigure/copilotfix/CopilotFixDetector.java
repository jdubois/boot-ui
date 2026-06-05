package io.github.jdubois.bootui.autoconfigure.copilotfix;

/**
 * Detects whether the GitHub Copilot SDK for Java is present on the application classpath.
 *
 * <p>BootUI never depends on the SDK directly; the "Fix it with Copilot" capability is an opt-in
 * feature that activates only when the host application adds the SDK itself. This mirrors the
 * classpath-marker approach used by {@code AiFrameworkDetector} for the AI Usage panel, keeping the
 * BootUI core free of a hard dependency on a large, optional library.
 */
public final class CopilotFixDetector {

    /**
     * Marker classes shipped by the GitHub Copilot SDK for Java. Several candidates are probed so
     * the detector keeps working across the SDK's preview and GA coordinates.
     */
    static final String[] SDK_CLASSES = {
        "com.github.copilot.sdk.CopilotClient",
        "com.github.copilot.CopilotClient",
        "io.github.copilot.sdk.CopilotClient"
    };

    private CopilotFixDetector() {}

    /** Returns {@code true} when the Copilot SDK is on the classpath. */
    public static boolean isSdkPresent() {
        return isSdkPresent(CopilotFixDetector.class.getClassLoader());
    }

    static boolean isSdkPresent(ClassLoader classLoader) {
        for (String className : SDK_CLASSES) {
            if (isClassPresent(className, classLoader)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClassPresent(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }
}
