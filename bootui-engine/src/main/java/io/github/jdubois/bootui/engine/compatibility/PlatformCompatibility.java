package io.github.jdubois.bootui.engine.compatibility;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Local, framework-free checks of declared host contracts, not a list of sampled CI patches. */
public final class PlatformCompatibility {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:[.\\-+].*)?$");

    private PlatformCompatibility() {}

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public record Finding(String id, Severity severity, String message, String action) {
        public String diagnostic() {
            return id + ": " + message + " " + action;
        }
    }

    public static List<Finding> spring(String bootVersion, String frameworkVersion, boolean boot4ApiPresent) {
        List<Finding> findings = new ArrayList<>();
        int[] boot = version(bootVersion);
        int[] framework = version(frameworkVersion);
        if (!boot4ApiPresent || (boot != null && boot[0] < 4) || (framework != null && framework[0] < 7)) {
            findings.add(new Finding(
                    "BOOTUI-COMPAT-001",
                    Severity.ERROR,
                    "BootUI requires Spring Boot 4 and Spring Framework 7 APIs. Detected Spring Boot "
                            + display(bootVersion) + " and Spring Framework " + display(frameworkVersion) + ".",
                    "Use a supported Spring Boot 4 platform or remove the BootUI starter; disabling BootUI does not"
                            + " repair incompatible transitive framework dependencies."));
        } else if ((boot != null && boot[0] > 4) || (framework != null && framework[0] > 7)) {
            findings.add(new Finding(
                    "BOOTUI-COMPAT-002",
                    Severity.WARNING,
                    "This Spring generation is outside BootUI's declared Spring Boot 4 / Spring Framework 7 target.",
                    "Verify compatibility before relying on BootUI diagnostics."));
        } else if (boot == null || framework == null) {
            findings.add(new Finding(
                    "BOOTUI-COMPAT-003",
                    Severity.INFO,
                    "Spring version metadata is unavailable or unrecognized; required Boot 4 API presence was checked.",
                    "Unknown metadata is not evidence of incompatibility."));
        }
        return List.copyOf(findings);
    }

    public static List<Finding> quarkus(String quarkusVersion) {
        int[] version = version(quarkusVersion);
        if (version == null) {
            return List.of(new Finding(
                    "BOOTUI-COMPAT-011",
                    Severity.INFO,
                    "Quarkus version metadata is unavailable or unrecognized.",
                    "Unknown metadata is not evidence of incompatibility."));
        }
        if (version[0] < 3 || (version[0] == 3 && version[1] < 33)) {
            return List.of(new Finding(
                    "BOOTUI-COMPAT-010",
                    Severity.ERROR,
                    "BootUI requires Quarkus 3.33 or later in the Quarkus 3 line. Detected Quarkus "
                            + display(quarkusVersion) + ".",
                    "Upgrade the Quarkus platform or remove the BootUI extension."));
        }
        if (version[0] > 3) {
            return List.of(new Finding(
                    "BOOTUI-COMPAT-012",
                    Severity.WARNING,
                    "This Quarkus generation is outside BootUI's declared Quarkus 3 target.",
                    "Verify compatibility before relying on BootUI diagnostics."));
        }
        return List.of();
    }

    private static int[] version(String value) {
        if (value == null) {
            return null;
        }
        var matcher = VERSION.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String display(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        String text = version.replaceAll("[\\p{Cntrl}]", "").strip();
        return text.substring(0, Math.min(text.length(), 100));
    }
}
