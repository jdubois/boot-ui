package io.github.jdubois.bootui.core;

import java.io.InputStream;
import java.util.Properties;

/**
 * Shared BootUI constants such as the artifact version baked at build time.
 */
public final class BootUiInfo {

    public static final String VERSION = readVersion();

    private BootUiInfo() {}

    private static String readVersion() {
        try (InputStream is = BootUiInfo.class.getResourceAsStream("/bootui-version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("version", "unknown");
            }
        } catch (Exception e) {
            // Intentionally swallow exception and fallback to "unknown" if version resource is unavailable or corrupt
        }
        return "unknown";
    }
}
