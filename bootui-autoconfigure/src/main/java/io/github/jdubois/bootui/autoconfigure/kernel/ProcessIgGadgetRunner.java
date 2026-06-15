package io.github.jdubois.bootui.autoconfigure.kernel;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Default {@link IgGadgetRunner} that shells out to the local Inspektor Gadget {@code ig} binary.
 *
 * <p>Captures are bounded and best-effort: streaming gadgets run with {@code --timeout} so {@code ig}
 * exits on its own, output is read on a daemon thread and capped at {@code maxEvents}, the process is
 * destroyed if it overruns a grace window, and gadget stderr is captured to a temporary file so the
 * reader can never deadlock on a full pipe. JSON decoding happens here so {@code KernelInsightsService}
 * only deals with normalized event maps.
 */
public class ProcessIgGadgetRunner implements IgGadgetRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessIgGadgetRunner.class);

    private static final int STDERR_MESSAGE_LIMIT = 2000;

    private final BootUiProperties.KernelInsights properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean versionResolved;
    private volatile @Nullable String cachedVersion;

    public ProcessIgGadgetRunner(BootUiProperties.KernelInsights properties) {
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return KernelInsightsSupport.available(properties);
    }

    @Override
    public @Nullable String unavailableReason() {
        return KernelInsightsSupport.unavailableReason(properties);
    }

    @Override
    public String igPath() {
        return properties.getIgPath();
    }

    @Override
    public @Nullable String igVersion() {
        if (versionResolved) {
            return cachedVersion;
        }
        synchronized (this) {
            if (versionResolved) {
                return cachedVersion;
            }
            cachedVersion = detectVersion();
            versionResolved = true;
            return cachedVersion;
        }
    }

    @Override
    public IgRunResult run(IgGadget gadget, Duration captureDuration, int maxEvents) {
        if (!available()) {
            return IgRunResult.error(unavailableReason());
        }
        long captureSeconds = Math.max(1, captureDuration.toSeconds());
        List<String> command = new ArrayList<>();
        command.add(properties.getIgPath());
        command.add("run");
        command.add(gadget.gadgetName());
        command.add("-o");
        command.add("json");
        // Mount debugfs/tracefs if they are not already mounted. The official ig container image does
        // this on startup; when BootUI invokes the ig binary directly the tracing filesystems may be
        // absent (notably inside a container), and ig aborts with "tracefs not mounted" without it.
        command.add("--auto-mount-filesystems");
        if (properties.isHostMode()) {
            // Trace host-wide and tolerate missing container enrichment. Without this, ig filters to
            // container activity and aborts when the container-detection hook cannot attach (as in
            // Docker Desktop's Linux VM), which would fail every capture inside a container.
            command.add("--host");
        }
        if (gadget.streaming()) {
            command.add("--timeout");
            command.add(Long.toString(captureSeconds));
        }

        Path stderrFile = null;
        Process process = null;
        try {
            stderrFile = Files.createTempFile("bootui-ig-", ".err");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(stderrFile.toFile());
            process = builder.start();

            List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());
            Process started = process;
            Thread reader =
                    new Thread(() -> readEvents(started, events, maxEvents), "bootui-ig-" + gadget.gadgetName());
            reader.setDaemon(true);
            reader.start();

            long graceSeconds = captureSeconds + 5;
            boolean exited = process.waitFor(graceSeconds, TimeUnit.SECONDS);
            if (!exited) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
            reader.join(TimeUnit.SECONDS.toMillis(2));

            List<Map<String, Object>> captured = new ArrayList<>(events);
            if (captured.isEmpty() && exited && process.exitValue() != 0) {
                return IgRunResult.error(stderrMessage(stderrFile, process.exitValue()));
            }
            return IgRunResult.ok(captured);
        } catch (IOException ex) {
            return IgRunResult.error("Failed to launch '" + properties.getIgPath() + "': " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return IgRunResult.error("Capture was interrupted");
        } finally {
            deleteQuietly(stderrFile);
        }
    }

    private void readEvents(Process process, List<Map<String, Object>> events, int maxEvents) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseInto(line, events, maxEvents);
                if (events.size() >= maxEvents) {
                    process.destroy();
                    return;
                }
            }
        } catch (IOException ex) {
            log.debug("BootUI Kernel Insights: error reading ig output: {}", ex.toString());
        }
    }

    private void parseInto(String line, List<Map<String, Object>> events, int maxEvents) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        char first = trimmed.charAt(0);
        try {
            if (first == '[') {
                List<?> array = objectMapper.readValue(trimmed, List.class);
                for (Object element : array) {
                    if (element instanceof Map<?, ?> map) {
                        addEvent(events, map);
                    }
                    if (events.size() >= maxEvents) {
                        return;
                    }
                }
            } else if (first == '{') {
                addEvent(events, objectMapper.readValue(trimmed, Map.class));
            }
        } catch (RuntimeException ex) {
            // Non-JSON output (warnings, progress) is expected; skip it.
            log.trace("BootUI Kernel Insights: skipping non-JSON ig line");
        }
    }

    @SuppressWarnings("unchecked")
    private void addEvent(List<Map<String, Object>> events, Map<?, ?> map) {
        Map<String, Object> event = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            event.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        events.add(event);
    }

    private @Nullable String detectVersion() {
        if (!available()) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(properties.getIgPath(), "version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String firstLine = output.lines().findFirst().orElse("").trim();
            return firstLine.isBlank() ? null : firstLine;
        } catch (IOException ex) {
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    private String stderrMessage(@Nullable Path stderrFile, int exitCode) {
        String detail = "";
        try {
            if (stderrFile != null && Files.isReadable(stderrFile)) {
                detail = Files.readString(stderrFile, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ex) {
            log.debug("BootUI Kernel Insights: could not read ig stderr: {}", ex.toString());
        }
        if (detail.length() > STDERR_MESSAGE_LIMIT) {
            detail = detail.substring(0, STDERR_MESSAGE_LIMIT) + "…";
        }
        return detail.isBlank() ? "ig exited with code " + exitCode : detail;
    }

    private void deleteQuietly(@Nullable Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.debug("BootUI Kernel Insights: could not delete temp file {}: {}", path, ex.toString());
        }
    }
}
