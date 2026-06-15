package io.github.jdubois.bootui.autoconfigure.kernel;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.KernelEventDto;
import io.github.jdubois.bootui.core.dto.KernelGadgetResult;
import io.github.jdubois.bootui.core.dto.KernelInsightsReport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Runs Inspektor Gadget captures for the Kernel Insights panel and normalizes the raw, gadget-specific
 * events into stable {@link KernelInsightsReport} DTOs.
 *
 * <p>Captures are user-initiated ({@link #scan()}) and bounded; {@link #status()} returns the last
 * capture or a ready/unavailable/disabled placeholder without running anything. Because eBPF gadget
 * schemas differ per gadget (and evolve), events are flattened heuristically into a {@code field}
 * map plus a best-effort one-line summary rather than bound to a per-gadget shape.
 */
public class KernelInsightsService {

    private static final int MAX_FIELDS = 14;

    private final BootUiProperties.KernelInsights properties;
    private final IgGadgetRunner runner;

    private volatile @Nullable KernelInsightsReport lastScan;

    public KernelInsightsService(BootUiProperties properties) {
        this(properties.getKernelInsights(), new ProcessIgGadgetRunner(properties.getKernelInsights()));
    }

    KernelInsightsService(BootUiProperties.KernelInsights properties, IgGadgetRunner runner) {
        this.properties = properties;
        this.runner = runner;
    }

    public KernelInsightsReport status() {
        KernelInsightsReport cached = this.lastScan;
        if (cached != null) {
            return cached;
        }
        boolean available = runner.available();
        String status = available ? "NOT_SCANNED" : "UNAVAILABLE";
        String message = available
                ? "Ready. Click Capture to run Inspektor Gadget for a few seconds and snapshot kernel activity."
                : runner.unavailableReason();
        return report(available, status, message, List.of());
    }

    public KernelInsightsReport scan() {
        if (!properties.isEnabled()) {
            return report(
                    false,
                    "DISABLED",
                    "Kernel Insights is disabled. Set bootui.kernel-insights.enabled=true to allow captures.",
                    List.of());
        }
        if (!runner.available()) {
            return report(false, "UNAVAILABLE", runner.unavailableReason(), List.of());
        }
        List<IgGadget> gadgets = selectedGadgets();
        if (gadgets.isEmpty()) {
            return report(
                    true, "ERROR", "No valid gadgets are configured in bootui.kernel-insights.gadgets.", List.of());
        }
        List<KernelGadgetResult> results = new ArrayList<>();
        for (IgGadget gadget : gadgets) {
            results.add(runGadget(gadget));
        }
        KernelInsightsReport report = report(true, "SCANNED", scanMessage(results), results);
        this.lastScan = report;
        return report;
    }

    private List<IgGadget> selectedGadgets() {
        Set<IgGadget> gadgets = new LinkedHashSet<>();
        for (String name : properties.getGadgets()) {
            IgGadget.byName(name).ifPresent(gadgets::add);
        }
        return new ArrayList<>(gadgets);
    }

    private KernelGadgetResult runGadget(IgGadget gadget) {
        Duration capture = properties.getCaptureDuration();
        int maxEvents = Math.max(1, properties.getMaxEvents());
        IgRunResult result = runner.run(gadget, capture, maxEvents);
        if (!result.ok()) {
            return new KernelGadgetResult(
                    gadget.gadgetName(),
                    gadget.title(),
                    gadget.category(),
                    "ERROR",
                    result.message() == null ? "Capture failed." : result.message(),
                    0,
                    List.of());
        }
        List<KernelEventDto> events =
                result.events().stream().map(event -> toEvent(gadget, event)).toList();
        String message = events.isEmpty()
                ? (gadget.streaming() ? "No events observed during the capture window." : "No entries found.")
                : "Captured " + events.size() + " event" + (events.size() == 1 ? "" : "s") + ".";
        return new KernelGadgetResult(
                gadget.gadgetName(), gadget.title(), gadget.category(), "OK", message, events.size(), events);
    }

    private KernelEventDto toEvent(IgGadget gadget, Map<String, Object> raw) {
        Map<String, String> flat = flatten(raw);
        String comm = first(flat, "comm", "proc.comm", "process.comm");
        String container = first(flat, "runtime.containername", "k8s.containername", "k8s.podname", "container");
        Integer pid = parseInt(first(flat, "pid", "proc.pid", "process.pid"));
        String timestamp = first(flat, "timestamp", "time");
        String summary = summarize(gadget, flat, comm);
        return new KernelEventDto(timestamp, blankToNull(comm), pid, blankToNull(container), summary, limit(flat));
    }

    private String summarize(IgGadget gadget, Map<String, String> flat, String comm) {
        String summary =
                switch (gadget.category()) {
                    case "NETWORK" -> {
                        String connection = address(flat, "src") + " → " + address(flat, "dst");
                        yield join(comm, connection);
                    }
                    case "DNS" -> {
                        String name = first(flat, "name", "dns.name");
                        String qtype = first(flat, "qtype", "dns.qtype");
                        String dns = name.isBlank() ? "" : (qtype.isBlank() ? name : name + " (" + qtype + ")");
                        yield join(comm, dns);
                    }
                    case "PROCESS" -> join(comm, first(flat, "args", "args_raw", "cmdline"));
                    case "SOCKET" -> {
                        String proto = first(flat, "proto", "protocol");
                        String state = first(flat, "state", "status");
                        String connection = address(flat, "src") + " → " + address(flat, "dst");
                        yield join(proto, connection, state);
                    }
                    default -> "";
                };
        return summary.isBlank() ? fallbackSummary(flat) : summary;
    }

    private String fallbackSummary(Map<String, String> flat) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : flat.entrySet()) {
            if (entry.getValue().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("  ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            if (builder.length() > 160) {
                break;
            }
        }
        return builder.toString();
    }

    /** Assemble an {@code ip:port} endpoint for the {@code src}/{@code dst} role from heuristic keys. */
    private String address(Map<String, String> flat, String role) {
        String ip = "src".equals(role)
                ? first(flat, "src", "src.addr", "saddr", "source")
                : first(flat, "dst", "dst.addr", "daddr", "destination");
        String port = "src".equals(role) ? first(flat, "src.port", "sport") : first(flat, "dst.port", "dport");
        if (ip.isBlank()) {
            return port.isBlank() ? "" : ":" + port;
        }
        return (port.isBlank() || ip.contains(":")) ? ip : ip + ":" + port;
    }

    private Map<String, String> flatten(Map<String, Object> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten("", raw, out);
        return out;
    }

    private void flatten(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
                flatten(key, entry.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            out.put(prefix, listToString(list));
        } else {
            out.put(prefix, value == null ? "" : String.valueOf(value));
        }
    }

    private String listToString(List<?> list) {
        boolean scalars = list.stream().noneMatch(element -> element instanceof Map || element instanceof List);
        if (scalars) {
            StringBuilder builder = new StringBuilder();
            for (Object element : list) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(element);
            }
            return builder.toString();
        }
        return String.valueOf(list);
    }

    private Map<String, String> limit(Map<String, String> flat) {
        if (flat.size() <= MAX_FIELDS) {
            return flat;
        }
        Map<String, String> limited = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : flat.entrySet()) {
            limited.put(entry.getKey(), entry.getValue());
            if (limited.size() >= MAX_FIELDS) {
                break;
            }
        }
        return limited;
    }

    /** Return the first non-blank value whose key equals or ends with {@code .<candidate>} (case-insensitive). */
    private String first(Map<String, String> flat, String... candidates) {
        for (String candidate : candidates) {
            String suffix = "." + candidate.toLowerCase();
            for (Map.Entry<String, String> entry : flat.entrySet()) {
                String key = entry.getKey().toLowerCase();
                if ((key.equals(candidate.toLowerCase()) || key.endsWith(suffix))
                        && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
        }
        return "";
    }

    private String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank() || "→".equals(part.trim()) || " → ".equals(part)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private @Nullable Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private @Nullable String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String scanMessage(List<KernelGadgetResult> results) {
        int totalEvents =
                results.stream().mapToInt(KernelGadgetResult::eventCount).sum();
        long errors = results.stream()
                .filter(result -> "ERROR".equals(result.status()))
                .count();
        String base = "Captured " + totalEvents + " kernel event" + (totalEvents == 1 ? "" : "s") + " across "
                + results.size() + " gadget" + (results.size() == 1 ? "" : "s") + ".";
        return errors == 0 ? base : base + " " + errors + " gadget" + (errors == 1 ? "" : "s") + " reported an error.";
    }

    private KernelInsightsReport report(
            boolean available, String status, String message, List<KernelGadgetResult> gadgets) {
        boolean scanned = "SCANNED".equals(status);
        return new KernelInsightsReport(
                available,
                status,
                message,
                System.getProperty("os.name"),
                runner.igPath(),
                scanned ? runner.igVersion() : null,
                ProcessHandle.current().pid(),
                scanned ? System.currentTimeMillis() : null,
                (int) Math.max(1, properties.getCaptureDuration().toSeconds()),
                gadgets);
    }
}
