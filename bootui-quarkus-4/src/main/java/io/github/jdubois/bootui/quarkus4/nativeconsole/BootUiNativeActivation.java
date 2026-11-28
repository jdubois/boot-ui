package io.github.jdubois.bootui.quarkus4.nativeconsole;

import io.quarkus.runtime.ImageMode;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ConfigUtils;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.ProfileConfigSourceInterceptor;
import io.smallrye.config.SmallRyeConfig;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

/** Runtime-only activation of a deliberately included native console. No configuration is retained. */
public final class BootUiNativeActivation {
    private static final Logger LOG = Logger.getLogger(BootUiNativeActivation.class);
    private static final String INVALID =
            "BootUI is inactive: invalid bootui.enabled configuration (fail-closed). Use AUTO, ON, or OFF.";
    private static final String RUNTIME_REQUIRED =
            "BootUI is inactive: native ON/local activation requires runtime system properties or environment variables.";

    private static boolean included;
    private static volatile Boolean active;

    private BootUiNativeActivation() {}

    static void initialize(boolean compiledIn) {
        included = compiledIn;
        active = null;
    }

    static synchronized void freeze() {
        if (active != null) {
            throw new IllegalStateException("BootUI native activation must be frozen exactly once.");
        }
        active = included
                && ImageMode.current() == ImageMode.NATIVE_RUN
                && evaluate(
                        ConfigProvider.getConfig().unwrap(SmallRyeConfig.class), ConfigUtils.getProfiles(), LOG::warn);
    }

    public static boolean isActive(LaunchMode launchMode) {
        return ImageMode.current() == ImageMode.NATIVE_RUN
                ? Boolean.TRUE.equals(active)
                : launchMode != LaunchMode.NORMAL;
    }

    /** Baked-in hooks must remain inert before the runtime freeze as well as after a denied decision. */
    public static boolean isNativeInactive() {
        return ImageMode.current() == ImageMode.NATIVE_RUN && !Boolean.TRUE.equals(active);
    }

    static boolean evaluate(SmallRyeConfig config, List<String> effectiveProfiles, Consumer<String> warning) {
        ConfigValue mode;
        try {
            mode = config.getConfigValue("bootui.enabled");
            String value = mode.getValue();
            boolean absent = value == null && mode.getConfigSourceName() == null;
            String normalized =
                    absent ? "OFF" : value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!List.of("ON", "OFF", "AUTO").contains(normalized)) {
                warning.accept(INVALID);
                return false;
            }
            if (!normalized.equals("ON")) {
                return false;
            }
        } catch (IllegalArgumentException invalidExpression) {
            warning.accept(INVALID);
            return false;
        }

        // Read the framework selector, not a profiled/indexed list or an independently guessed environment alias.
        ConfigValue profile = config.getConfigValue(SmallRyeConfig.SMALLRYE_CONFIG_PROFILE);
        if (!runtimeValue(config, mode) || !runtimeValue(config, profile)) {
            warning.accept(RUNTIME_REQUIRED);
            return false;
        }
        return profile.getProfile() == null
                && ProfileConfigSourceInterceptor.convertProfile(profile.getValue())
                        .contains("local")
                && effectiveProfiles.contains("local")
                && effectiveProfiles.stream().noneMatch(BootUiNativeActivation::productionProfile);
    }

    private static boolean productionProfile(String profile) {
        String normalized = profile.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }

    private static boolean runtimeValue(SmallRyeConfig config, ConfigValue value) {
        if (value.getValue() == null
                || !value.getValue().equals(value.getRawValue())
                || value.getConfigSourceName() == null) {
            return false;
        }
        ConfigSource selected = null;
        for (ConfigSource source : config.getConfigSources()) {
            if (value.getConfigSourceName().equals(source.getName())) {
                // SPI source names are not unique; matching one ordinal does not prove which source was selected.
                if (selected != null) {
                    return false;
                }
                selected = source;
            }
        }
        if (selected == null || selected.getOrdinal() != value.getConfigSourceOrdinal()) {
            return false;
        }
        String type = selected.getClass().getName();
        return type.equals("io.smallrye.config.SysPropConfigSource")
                || type.equals("io.smallrye.config.EnvConfigSource");
    }
}
