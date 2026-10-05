package io.github.jdubois.bootui.autoconfigure;

import java.util.List;

/**
 * Default constants used by BootUI configuration and activation conditions.
 */
public final class BootUiDefaults {

    public static final List<String> ENABLED_PROFILES = List.of("dev", "local");
    public static final List<String> DISABLED_PROFILES = List.of("prod", "production");

    private BootUiDefaults() {
        // Utility class
    }
}
