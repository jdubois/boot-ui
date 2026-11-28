package io.github.jdubois.bootui.quarkus4.nativeconsole;

import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.Set;

/** Initial native bindings: live APIs and recorded metadata, not JVM or runtime bytecode diagnostics. */
public final class BootUiNativeCapabilities {
    private static final Set<String> SUPPORTED = Set.of(
            BootUiPanels.OVERVIEW,
            BootUiPanels.HEALTH,
            BootUiPanels.CONFIG,
            BootUiPanels.PROFILE_DIFF,
            BootUiPanels.LOGGERS,
            BootUiPanels.BEANS,
            BootUiPanels.MAPPINGS,
            BootUiPanels.DATABASE_CONNECTION_POOLS,
            BootUiPanels.TRACES,
            BootUiPanels.LOG_TAIL,
            BootUiPanels.EXCEPTIONS,
            BootUiPanels.HTTP_EXCHANGES,
            BootUiPanels.HTTP_PROBE,
            BootUiPanels.SQL_TRACE,
            BootUiPanels.MCP_SERVER,
            BootUiPanels.CLI,
            BootUiPanels.ACTIVITY,
            BootUiPanels.AI,
            BootUiPanels.VULNERABILITIES);

    private static final Set<String> JVM_DIAGNOSTICS = Set.of(
            BootUiPanels.THREADS,
            BootUiPanels.HEAP_DUMP,
            BootUiPanels.LIVE_MEMORY,
            BootUiPanels.JVM_TUNING,
            BootUiPanels.MEMORY,
            BootUiPanels.RUNTIME_INSIGHTS);

    private static final Set<String> AGENT_DIAGNOSTICS = Set.of(
            BootUiPanels.JAVA_AGENT, BootUiPanels.CODE_INVENTORY, BootUiPanels.CODE_PATHS, BootUiPanels.SIDE_EFFECTS);

    private BootUiNativeCapabilities() {}

    /** A native ceiling; the ordinary optional-capability and panel policies still apply. */
    public static boolean supported(String panelId) {
        return SUPPORTED.contains(panelId);
    }

    public static String unavailableReason(String panelId) {
        if (supported(panelId)) {
            return null;
        }
        if (JVM_DIAGNOSTICS.contains(panelId)) {
            return "Not supported in the local Quarkus native console: this binding requires JVM management,"
                    + " heap/JFR or runtime bytecode diagnostics.";
        }
        if (AGENT_DIAGNOSTICS.contains(panelId)) {
            return "Not applicable in a Quarkus native executable: the BootUI Java agent instruments JVM bytecode.";
        }
        return "Not yet supported in the local Quarkus native console. The Quarkus JVM binding remains available"
                + " when its ordinary capability requirements are met.";
    }
}
