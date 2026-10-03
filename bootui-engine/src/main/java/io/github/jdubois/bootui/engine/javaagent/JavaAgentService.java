package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.core.dto.JavaAgentClaimDto;
import io.github.jdubois.bootui.core.dto.JavaAgentCountersDto;
import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.core.dto.JavaAgentRetransformationDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSetupDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Java Agent panel ({@code docs/PLAN-v2.md} §5.13): maps the bootstrap bridge's status and this application's
 * claim into one report, with the setup snippets that attach the agent. Framework-neutral; each adapter builds it with
 * its stack and its own claim. Reading the report only reads the bridge: it claims, installs, and sends nothing.
 */
public final class JavaAgentService {

    static final String NOT_ATTACHED_REASON =
            "This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets"
                    + " to add agent-based evidence.";
    static final String NATIVE_IMAGE_REASON = "A native image cannot load a Java agent.";
    static final String NOT_STARTED_REASON =
            "The BootUI agent bridge is on the bootstrap class path, but the agent did not start";
    static final String NOT_ENABLED_REASON =
            "bootui.agent.enabled is false: this application leaves the agent dormant and claims nothing.";
    static final String NOT_CLAIMED_REASON = "This application has not claimed the agent.";
    static final String DISARMED_REASON =
            "This run ended its claim: the agent records nothing until the next run claims it.";
    static final String RELEASED_REASON = "The agent was released: no application holds it.";
    static final String REPLACED_REASON = "Another application's claim replaced this run's claim, and it has ended.";

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claim;
    private final JavaAgentSettings settings;

    /**
     * @param access the bridge, usually {@link AgentBridgeAccess#locate()}
     * @param claim this application's current claim, or a supplier of {@code null} when it made none
     * @param settings the application's settings
     */
    public JavaAgentService(AgentBridgeAccess access, Supplier<AgentClaim> claim, JavaAgentSettings settings) {
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.claim = claim == null ? () -> null : claim;
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** The agent's status for this application. */
    public JavaAgentReport report() {
        Map<String, Object> status = access.status();
        Map<String, Object> agent = AgentBridgeAccess.map(status, "agent");
        Map<String, Object> bridgeClaim = AgentBridgeAccess.map(status, "claim");
        String agentVersion = AgentBridgeAccess.text(agent, "version");
        String jar = AgentBridgeAccess.text(agent, "jar");
        Resolution resolution = resolve(status, bridgeClaim);
        List<String> warnings = new ArrayList<>();
        String bootUiVersion = settings.bootUiVersion();
        if (agentVersion != null
                && bootUiVersion != null
                && !"unknown".equals(bootUiVersion)
                && !agentVersion.equals(bootUiVersion)) {
            warnings.add("The attached agent is version " + agentVersion + " and BootUI is version " + bootUiVersion
                    + ": they speak the same protocol, but attach the bootui-agent jar of BootUI " + bootUiVersion
                    + ".");
        }
        JavaAgentSetupDto setup = AgentSetupSnippets.setup(
                bootUiVersion,
                jar,
                settings.repository(),
                settings.stack(),
                AgentSetupSnippets.detectBuildTool(settings.workingDirectory()));
        return new JavaAgentReport(
                resolution.state(),
                resolution.reason(),
                agentVersion,
                bootUiVersion,
                access.protocol(),
                AgentBridgeAccess.EXPECTED_PROTOCOL,
                jdk(),
                AgentBridgeAccess.text(agent, "loadMode"),
                jar,
                AgentBridgeAccess.number(agent, "startupMicros"),
                claimDto(bridgeClaim),
                resolution.heldBy(),
                sensors(agent),
                retransformation(AgentBridgeAccess.map(agent, "installer")),
                counters(AgentBridgeAccess.map(status, "counters")),
                strings(AgentBridgeAccess.items(status, "messages")),
                warnings,
                setup);
    }

    private Resolution resolve(Map<String, Object> status, Map<String, Object> bridgeClaim) {
        if (settings.disabledReason() != null) {
            return new Resolution(JavaAgentReport.DISABLED, settings.disabledReason(), null);
        }
        if (settings.nativeImage()) {
            return new Resolution(JavaAgentReport.UNAVAILABLE, NATIVE_IMAGE_REASON, null);
        }
        if (!access.present()) {
            return new Resolution(JavaAgentReport.NOT_ATTACHED, NOT_ATTACHED_REASON, null);
        }
        if (!access.compatible()) {
            return new Resolution(
                    JavaAgentReport.UNAVAILABLE,
                    access.problem() + ", so BootUI leaves it unused: attach the bootui-agent jar of BootUI "
                            + settings.bootUiVersion() + ".",
                    null);
        }
        if (!AgentBridgeAccess.flag(status, "attached")) {
            List<String> messages = strings(AgentBridgeAccess.items(status, "messages"));
            return new Resolution(
                    JavaAgentReport.UNAVAILABLE,
                    NOT_STARTED_REASON + (messages.isEmpty() ? "." : ": " + messages.get(messages.size() - 1)),
                    null);
        }
        String currentOwner = AgentBridgeAccess.text(bridgeClaim, "owner");
        boolean currentArmed = AgentBridgeAccess.flag(bridgeClaim, "armed");
        boolean currentHeld = currentArmed && !AgentBridgeAccess.flag(bridgeClaim, "abandoned");
        AgentClaim ours = claim.get();
        if (ours == null) {
            if (currentHeld) {
                return held(currentOwner, bridgeClaim);
            }
            return new Resolution(
                    JavaAgentReport.DORMANT, settings.enabled() ? NOT_CLAIMED_REASON : NOT_ENABLED_REASON, null);
        }
        String claimed = ours.claimStatus();
        Map<String, Object> result = ours.result();
        switch (claimed) {
            case AgentClaim.FAILED -> {
                return new Resolution(JavaAgentReport.FAILED, reason(result, "The agent failed this claim."), null);
            }
            case AgentClaim.HELD -> {
                if (currentHeld && !ours.owner().equals(currentOwner)) {
                    return held(currentOwner, bridgeClaim);
                }
                return new Resolution(
                        JavaAgentReport.DORMANT,
                        "Another application held the agent when this one started and has since let it go: restart"
                                + " this application to claim it.",
                        null);
            }
            case AgentClaim.ARMED, AgentClaim.DISARMED -> {
                if (ours.owner().equals(currentOwner)) {
                    return currentArmed
                            ? new Resolution(JavaAgentReport.ARMED, null, null)
                            : new Resolution(JavaAgentReport.DISARMED, DISARMED_REASON, null);
                }
                if (currentHeld) {
                    return held(currentOwner, bridgeClaim);
                }
                return new Resolution(
                        JavaAgentReport.DORMANT, currentOwner == null ? RELEASED_REASON : REPLACED_REASON, null);
            }
            default -> {
                return new Resolution(
                        JavaAgentReport.UNAVAILABLE, reason(result, "The agent could not be claimed."), null);
            }
        }
    }

    private static Resolution held(String owner, Map<String, Object> holder) {
        String mode = AgentBridgeAccess.text(holder, "mode");
        String by = owner == null ? "another application" : owner;
        return new Resolution(
                JavaAgentReport.HELD,
                "Another application in this JVM holds the agent: " + by + (mode == null ? "" : " (" + mode + ")")
                        + ". A dev application takes it over from a test application.",
                owner);
    }

    private static String reason(Map<String, Object> result, String fallback) {
        String reason = AgentBridgeAccess.text(result, "reason");
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private static JavaAgentClaimDto claimDto(Map<String, Object> claim) {
        if (claim.isEmpty()) {
            return null;
        }
        Long generation = AgentBridgeAccess.number(claim, "generation");
        return new JavaAgentClaimDto(
                generation == null ? 0L : generation,
                AgentBridgeAccess.text(claim, "owner"),
                AgentBridgeAccess.text(claim, "application"),
                AgentBridgeAccess.text(claim, "mode"),
                AgentBridgeAccess.number(claim, "armedAt"),
                strings(AgentBridgeAccess.items(claim, "packages")),
                AgentBridgeAccess.flag(claim, "armed"),
                AgentBridgeAccess.flag(claim, "abandoned"));
    }

    private static List<JavaAgentSensorDto> sensors(Map<String, Object> agent) {
        List<JavaAgentSensorDto> sensors = new ArrayList<>();
        for (Object item : AgentBridgeAccess.items(agent, "sensors")) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> sensor = AgentBridgeAccess.map(Map.of("sensor", raw), "sensor");
                Long types = AgentBridgeAccess.number(sensor, "instrumentedTypes");
                sensors.add(new JavaAgentSensorDto(
                        AgentBridgeAccess.text(sensor, "id"),
                        AgentBridgeAccess.text(sensor, "state"),
                        types == null ? 0 : types.intValue(),
                        strings(AgentBridgeAccess.items(sensor, "failures"))));
            }
        }
        return sensors;
    }

    private static JavaAgentRetransformationDto retransformation(Map<String, Object> installer) {
        if (installer.isEmpty()) {
            return null;
        }
        return new JavaAgentRetransformationDto(
                AgentBridgeAccess.text(installer, "state"),
                count(installer, "transformed"),
                count(installer, "retransformed"),
                count(installer, "failed"),
                count(installer, "skipped"),
                longValue(installer, "durationMillis"),
                AgentBridgeAccess.flag(installer, "running"));
    }

    private static JavaAgentCountersDto counters(Map<String, Object> counters) {
        if (counters.isEmpty()) {
            return null;
        }
        return new JavaAgentCountersDto(
                longValue(counters, "claims"),
                longValue(counters, "takeovers"),
                longValue(counters, "holds"),
                longValue(counters, "staleTokens"),
                longValue(counters, "errors"));
    }

    private static int count(Map<String, Object> map, String key) {
        return (int) Math.min(Integer.MAX_VALUE, longValue(map, key));
    }

    private static long longValue(Map<String, Object> map, String key) {
        Long value = AgentBridgeAccess.number(map, key);
        return value == null ? 0L : value;
    }

    private static List<String> strings(Iterable<?> items) {
        List<String> list = new ArrayList<>();
        for (Object item : items) {
            if (item != null) {
                list.add(String.valueOf(item));
            }
        }
        return list;
    }

    private static String jdk() {
        String vendor = System.getProperty("java.vendor");
        return Runtime.version() + (vendor == null || vendor.isBlank() ? "" : " (" + vendor + ")");
    }

    private record Resolution(String state, String reason, String heldBy) {}
}
