package io.github.jdubois.bootui.autoconfigure.kernel;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Cheap, side-effect-free availability checks for the Kernel Insights panel.
 *
 * <p>Inspektor Gadget is eBPF-based and therefore only works on a Linux host with the {@code ig}
 * binary present (and, at capture time, sufficient privileges). These helpers never spawn a process
 * so {@code PanelsController} can call them on every panel-availability request: the binary is
 * located by inspecting the configured path or scanning {@code PATH} entries on disk.
 */
public final class KernelInsightsSupport {

    private KernelInsightsSupport() {}

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    /** Whether the panel can run a capture: feature enabled, running on Linux, and {@code ig} present. */
    public static boolean available(BootUiProperties.KernelInsights properties) {
        return properties.isEnabled()
                && isLinux()
                && locate(properties.getIgPath()).isPresent();
    }

    public static @Nullable String unavailableReason(BootUiProperties.KernelInsights properties) {
        if (!properties.isEnabled()) {
            return "Kernel Insights is disabled. Set bootui.kernel-insights.enabled=true to enable it.";
        }
        if (!isLinux()) {
            return "Inspektor Gadget requires a Linux host with eBPF support; this machine is "
                    + System.getProperty("os.name", "non-Linux") + ".";
        }
        if (locate(properties.getIgPath()).isEmpty()) {
            return "The Inspektor Gadget 'ig' binary (" + properties.getIgPath()
                    + ") was not found on PATH. Install it from https://inspektor-gadget.io.";
        }
        return null;
    }

    /**
     * Resolve the {@code ig} binary to an executable file. An absolute or path-qualified command is
     * checked directly; a bare command name is searched across {@code PATH} entries.
     */
    public static Optional<Path> locate(String igPath) {
        if (igPath == null || igPath.isBlank()) {
            return Optional.empty();
        }
        if (igPath.contains(File.separator) || igPath.contains("/")) {
            Path candidate = Path.of(igPath);
            return isExecutableFile(candidate) ? Optional.of(candidate) : Optional.empty();
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        for (String entry : pathEnv.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry, igPath);
            if (isExecutableFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isExecutableFile(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
