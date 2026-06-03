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
 *     <li>{@code bootui.enabled=ON}</li>
 *     <li>An active profile is present in {@code bootui.enabled-profiles}</li>
 *     <li>{@code spring-boot-devtools} is on the classpath</li>
 * </ul>
 * and none of these is true:
 * <ul>
 *     <li>{@code bootui.enabled=OFF}</li>
 *     <li>An active profile is present in {@code bootui.disabled-profiles}</li>
 * </ul>
 */
public class BootUiActivationCondition implements Condition {

    public static final String DEVTOOLS_CLASS = "org.springframework.boot.devtools.restart.RestartScope";

    public static BootUiActivation resolve(Environment environment, ClassLoader classLoader) {
        String mode = environment.getProperty("bootui.enabled", "AUTO").trim().toUpperCase(Locale.ROOT);
        List<String> warnings = new ArrayList<>();
        Collection<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());

        List<String> disabledProfiles =
                listProperty(environment, "bootui.disabled-profiles", BootUiDefaults.DISABLED_PROFILES);
        List<String> enabledProfiles =
                listProperty(environment, "bootui.enabled-profiles", BootUiDefaults.ENABLED_PROFILES);

        if (!List.of("AUTO", "ON", "OFF").contains(mode)) {
            return new BootUiActivation(false, "Disabled: invalid bootui.enabled value '" + mode + "'", warnings);
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
