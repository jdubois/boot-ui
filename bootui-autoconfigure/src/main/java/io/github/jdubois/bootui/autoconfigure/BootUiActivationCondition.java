package io.github.jdubois.bootui.autoconfigure;

import java.util.*;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

/**
 * Custom condition that controls when {@link BootUiAutoConfiguration} activates.
 *
 * <p>BootUI activates only when at least one of these is true:
 * <ul>
 *     <li>{@code bootui.enabled=ON} (also {@code true}/{@code yes}; in YAML {@code ON} is parsed as
 *         a boolean, so it arrives as {@code true})</li>
 *     <li>An active profile is present in {@code bootui.enabled-profiles}</li>
 *     <li>{@code spring-boot-devtools} is on the classpath</li>
 * </ul>
 * and none of these is true:
 * <ul>
 *     <li>{@code bootui.enabled=OFF} (also {@code false}/{@code no})</li>
 *     <li>An active profile is present in {@code bootui.disabled-profiles}</li>
 * </ul>
 */
public class BootUiActivationCondition implements Condition {

    public static final String DEVTOOLS_CLASS = "org.springframework.boot.devtools.restart.RestartScope";

    public static BootUiActivation resolve(Environment environment, ClassLoader classLoader) {
        String rawMode = environment.getProperty("bootui.enabled", "AUTO").trim();
        String mode = normalizeMode(rawMode);
        List<String> warnings = new ArrayList<>();
        Collection<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());

        List<String> disabledProfiles =
                listProperty(environment, "bootui.disabled-profiles", BootUiDefaults.DISABLED_PROFILES);
        List<String> enabledProfiles =
                listProperty(environment, "bootui.enabled-profiles", BootUiDefaults.ENABLED_PROFILES);

        if (!List.of("AUTO", "ON", "OFF").contains(mode)) {
            return new BootUiActivation(false, "Disabled: invalid bootui.enabled value '" + rawMode + "'", warnings);
        }

        for (String profile : disabledProfiles) {
            if (activeProfiles.contains(profile)) {
                if ("ON".equals(mode)) {
                    warnings.add("Profile '" + profile
                            + "' is in bootui.disabled-profiles but bootui.enabled=ON forces it on.");
                    return new BootUiActivation(
                            true,
                            "Explicitly enabled (bootui.enabled=ON) despite disabled profile '" + profile + "'",
                            warnings);
                }
                return new BootUiActivation(
                        false,
                        "Disabled because active profile '" + profile + "' is in bootui.disabled-profiles",
                        warnings);
            }
        }

        if ("OFF".equals(mode)) {
            return new BootUiActivation(false, "Disabled by bootui.enabled=OFF", warnings);
        }
        if ("ON".equals(mode)) {
            return new BootUiActivation(true, "Enabled by bootui.enabled=ON", warnings);
        }

        for (String profile : enabledProfiles) {
            if (activeProfiles.contains(profile)) {
                return new BootUiActivation(true, "Enabled by active profile '" + profile + "'", warnings);
            }
        }

        if (ClassUtils.isPresent(DEVTOOLS_CLASS, classLoader)) {
            return new BootUiActivation(true, "Enabled because spring-boot-devtools is on the classpath", warnings);
        }

        return new BootUiActivation(
                false, "Disabled: no enabled profile and devtools is not on the classpath", warnings);
    }

    /**
     * Normalizes a configured {@code bootui.enabled} value to a canonical {@code AUTO}/{@code ON}/
     * {@code OFF} token.
     *
     * <p>YAML treats {@code on}/{@code off}/{@code yes}/{@code no}/{@code true}/{@code false} as
     * booleans, so {@code bootui.enabled: ON} is delivered to the {@link Environment} as the string
     * {@code "true"}. To keep the documented {@code ON}/{@code OFF} switch working in YAML (and to
     * stay consistent with Spring's relaxed binding of the {@code Mode} enum used everywhere else),
     * boolean-ish values are mapped onto {@code ON}/{@code OFF}. Genuinely unknown values are passed
     * through unchanged so they still fail closed.</p>
     */
    private static String normalizeMode(String rawMode) {
        String mode = rawMode.toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "ON", "TRUE", "YES" -> "ON";
            case "OFF", "FALSE", "NO" -> "OFF";
            default -> mode;
        };
    }

    private static List<String> listProperty(Environment env, String key, List<String> defaults) {
        String raw = env.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaults;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return resolve(context.getEnvironment(), context.getClassLoader()).enabled();
    }
}
