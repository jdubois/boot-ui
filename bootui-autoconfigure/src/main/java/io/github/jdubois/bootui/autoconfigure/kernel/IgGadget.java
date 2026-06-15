package io.github.jdubois.bootui.autoconfigure.kernel;

import java.util.Arrays;
import java.util.Optional;

/**
 * Curated catalog of the Inspektor Gadget gadgets surfaced by the Kernel Insights panel.
 *
 * <p>Each entry records the {@code ig} gadget name, a human title, the UI category, and whether the
 * gadget streams events (and therefore needs a bounded capture window) or returns an immediate
 * point-in-time snapshot. The set is intentionally small and high-signal for a local development
 * console: process executions, outbound TCP connections, DNS lookups, and the current open sockets.
 */
public enum IgGadget {
    TRACE_EXEC("trace_exec", "Process executions", "PROCESS", true),
    TRACE_TCP("trace_tcp", "TCP connections", "NETWORK", true),
    TRACE_DNS("trace_dns", "DNS lookups", "DNS", true),
    SNAPSHOT_SOCKET("snapshot_socket", "Open sockets", "SOCKET", false);

    private final String gadgetName;
    private final String title;
    private final String category;
    private final boolean streaming;

    IgGadget(String gadgetName, String title, String category, boolean streaming) {
        this.gadgetName = gadgetName;
        this.title = title;
        this.category = category;
        this.streaming = streaming;
    }

    public String gadgetName() {
        return gadgetName;
    }

    public String title() {
        return title;
    }

    public String category() {
        return category;
    }

    /**
     * Whether the gadget emits a continuous event stream (captured for a bounded window) rather than
     * a single immediate snapshot.
     */
    public boolean streaming() {
        return streaming;
    }

    public static Optional<IgGadget> byName(String gadgetName) {
        return Arrays.stream(values())
                .filter(gadget -> gadget.gadgetName.equalsIgnoreCase(gadgetName))
                .findFirst();
    }
}
