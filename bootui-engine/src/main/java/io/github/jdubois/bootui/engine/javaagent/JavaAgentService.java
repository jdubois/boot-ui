package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.core.dto.JavaAgentClaimDto;
import io.github.jdubois.bootui.core.dto.JavaAgentCodePathsCountersDto;
import io.github.jdubois.bootui.core.dto.JavaAgentCountersDto;
import io.github.jdubois.bootui.core.dto.JavaAgentExecutorCountersDto;
import io.github.jdubois.bootui.core.dto.JavaAgentHookDto;
import io.github.jdubois.bootui.core.dto.JavaAgentInventoryCountersDto;
import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.core.dto.JavaAgentRetransformationDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorToggleDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSetupDto;
import io.github.jdubois.bootui.core.dto.SideEffectsHookDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** What propagated work, the {@code PROPAGATED} tier, and {@code work-after-response} need. */
    public static final String PROPAGATION_REQUIREMENT = "Requires the BootUI agent's executors sensor";

    /** What Code Inventory and {@code changed-code-not-executed} need ({@code docs/PLAN-v2.md} §5.15). */
    public static final String INVENTORY_REQUIREMENT = "Requires the BootUI agent's inventory sensor";

    /** What Code Paths and the request trees need ({@code docs/PLAN-v2.md} §5.14). */
    public static final String CODE_PATHS_REQUIREMENT = "Requires the BootUI agent's code-paths sensor";

    /** What Side Effects needs ({@code docs/PLAN-v2.md} §5.16). */
    public static final String SIDE_EFFECTS_REQUIREMENT = "Requires the BootUI agent";

    /** The state the agent reports for a sensor whose hooks are in place. */
    static final String INSTALLED = "installed";

    /** The JDK feature releases the executors sensor's hooks were verified on. */
    static final List<Integer> VERIFIED_JDKS = List.of(17, 21, 25, 26, 27);

    static final String UNVERIFIED_JDK_WARNING = "The agent's executor hooks are not verified on JDK %d: the self-test"
            + " decides whether each hook propagates.";

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claim;
    private final JavaAgentSettings settings;
    private final List<java.util.function.Consumer<String>> switchListeners = new CopyOnWriteArrayList<>();
    /** The agent's failure of a switch the bridge committed, by sensor id, for the claim revision it made. */
    private final Map<String, SwitchFailure> switchFailures = new java.util.concurrent.ConcurrentHashMap<>();

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
        if (!AgentBridgeAccess.items(agent, "sensors").isEmpty()
                && !verifiedJdk(Runtime.version().feature())) {
            warnings.add(UNVERIFIED_JDK_WARNING.formatted(Runtime.version().feature()));
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
                sensors(agent, status, claimedSensors(resolution)),
                toggles(resolution, agent),
                retransformation(agent),
                counters(AgentBridgeAccess.map(status, "counters")),
                strings(AgentBridgeAccess.items(status, "messages")),
                warnings,
                setup);
    }

    /**
     * Whether the agent is attached and this application's claim is armed, so agent-based evidence is recorded for it.
     * Reads only the bridge's status. Never throws.
     */
    public boolean recording() {
        try {
            Map<String, Object> status = access.status();
            return JavaAgentReport.ARMED.equals(
                    resolve(status, AgentBridgeAccess.map(status, "claim")).state());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Whether the agent propagates the work a request hands to a JDK executor for this application: it is attached and
     * armed for it ({@link #recording()}), its {@code executors} sensor is installed and self-tested, the bridge has not disabled
     * propagation, and this application attached its handoffs to its claim. Reads only the bridge's status. Never
     * throws.
     */
    public boolean propagating() {
        return propagationUnavailableReason() == null;
    }

    /**
     * Why the agent does not propagate executor work for this application, starting with
     * {@value #PROPAGATION_REQUIREMENT}, or {@code null} when it does ({@link #propagating()}). Never throws.
     */
    public String propagationUnavailableReason() {
        try {
            Map<String, Object> status = access.status();
            String reason = sensorUnavailableReason(
                    status,
                    PROPAGATION_REQUIREMENT,
                    AgentSensorSettings.EXECUTORS,
                    "executors",
                    "executor propagation");
            if (reason != null) {
                return reason;
            }
            AgentClaim ours = claim.get();
            if (ours == null || ours.handoffs() == null) {
                return PROPAGATION_REQUIREMENT + ": BootUI has not attached its executor handoffs to this application's"
                        + " claim yet.";
            }
            return null;
        } catch (RuntimeException ex) {
            return PROPAGATION_REQUIREMENT + ".";
        }
    }

    /**
     * Why the agent's {@code inventory} sensor does not record this application's run, starting with
     * {@value #INVENTORY_REQUIREMENT}, or {@code null} when it does: the agent is attached and armed for this
     * application, its bridge carries the sensor's entry points, the sensor is installed, and the bridge has not
     * disabled it ({@code docs/PLAN-v2.md} §5.15). Reads only the bridge's status. Never throws.
     */
    public String inventoryUnavailableReason() {
        try {
            if (access.present() && access.compatible() && !access.inventorySupported()) {
                return INVENTORY_REQUIREMENT + ": the attached BootUI agent predates it; attach the bootui-agent jar of"
                        + " BootUI " + settings.bootUiVersion() + ".";
            }
            return sensorUnavailableReason(
                    access.status(), INVENTORY_REQUIREMENT, AgentSensorSettings.INVENTORY, "inventory", "the sensor");
        } catch (RuntimeException ex) {
            return INVENTORY_REQUIREMENT + ".";
        }
    }

    /**
     * Why the agent's {@code code-paths} sensor does not record this application's requests, starting with
     * {@value #CODE_PATHS_REQUIREMENT}, or {@code null} when it does: the agent is attached and armed for this
     * application, its bridge carries the sensor's entry points, the sensor is installed, and the bridge has not
     * disabled it ({@code docs/PLAN-v2.md} §5.14). Reads only the bridge's status. Never throws.
     */
    public String codePathsUnavailableReason() {
        try {
            if (access.present() && access.compatible() && !access.codePathsSupported()) {
                return CODE_PATHS_REQUIREMENT
                        + ": the attached BootUI agent predates it; attach the bootui-agent jar of" + " BootUI "
                        + settings.bootUiVersion() + ".";
            }
            return sensorUnavailableReason(
                    access.status(),
                    CODE_PATHS_REQUIREMENT,
                    AgentSensorSettings.CODE_PATHS,
                    AgentSensorSettings.CODE_PATHS,
                    "the sensor");
        } catch (RuntimeException ex) {
            return CODE_PATHS_REQUIREMENT + ".";
        }
    }

    /**
     * Why Side Effects records nothing for this application, starting with {@value #SIDE_EFFECTS_REQUIREMENT}, or
     * {@code null} when the agent is attached and armed for it with a bridge carrying the side-effect sensors' entry
     * points ({@code docs/PLAN-v2.md} §5.16). Each sensor's own coverage is {@link #sideEffectsCoverage}. Reads only the
     * bridge's status. Never throws.
     */
    public String sideEffectsUnavailableReason() {
        try {
            if (access.present() && access.compatible() && !access.sideEffectsSupported()) {
                return SIDE_EFFECTS_REQUIREMENT + ": the attached BootUI agent predates Side Effects; attach the"
                        + " bootui-agent jar of BootUI " + settings.bootUiVersion() + ".";
            }
            Resolution resolution = resolve(access.status(), AgentBridgeAccess.map(access.status(), "claim"));
            if (!JavaAgentReport.ARMED.equals(resolution.state())) {
                String reason = resolution.reason() == null
                        ? "the agent is not armed for this application."
                        : resolution.reason();
                if (JavaAgentReport.NOT_ATTACHED.equals(resolution.state())) {
                    reason += " Start the application with -javaagent:bootui-agent.jar (see the Java Agent panel).";
                }
                return SIDE_EFFECTS_REQUIREMENT + ": " + reason;
            }
            return null;
        } catch (RuntimeException ex) {
            return SIDE_EFFECTS_REQUIREMENT + ".";
        }
    }

    /**
     * One Side Effects sensor's coverage for this application: whether it records, and why not, with its hooks as the
     * agent reports them and the records the bridge dropped for it ({@code docs/PLAN-v2.md} §5.16). Never throws.
     */
    public SideEffectsCoverage sideEffectsCoverage(String id) {
        try {
            String unavailable = sideEffectsUnavailableReason();
            if (unavailable != null) {
                return new SideEffectsCoverage(SideEffectsSensorDto.UNAVAILABLE, unavailable, List.of(), 0L);
            }
            Map<String, Object> status = access.status();
            Map<String, Object> counters = AgentBridgeAccess.map(status, id);
            Map<String, Object> sensor = sensor(AgentBridgeAccess.map(status, "agent"), id);
            List<SideEffectsHookDto> hooks = new ArrayList<>();
            if (sensor != null) {
                for (JavaAgentHookDto hook : hooks(sensor, counters)) {
                    hooks.add(new SideEffectsHookDto(
                            hook.id(), hook.type(), hook.present(), hook.transformed(), hook.selfTest(), hook.fired()));
                }
            }
            long dropped = longValue(counters, "dropped");
            AgentClaim ours = claim.get();
            JavaAgentSensorToggleDto toggle = ours == null ? null : toggle(ours, sensor, id);
            if (ours != null && !ours.uses(id)) {
                return new SideEffectsCoverage(
                        SideEffectsSensorDto.NOT_CLAIMED,
                        Boolean.FALSE.equals(ours.sensorOverrides().get(id))
                                ? "Switched off at run time: this application's bootui.agent.sensors includes " + id
                                        + "."
                                : "This application's bootui.agent.sensors does not include " + id + ".",
                        hooks,
                        dropped,
                        Map.of(),
                        toggle);
            }
            if (sensor == null) {
                return new SideEffectsCoverage(
                        SideEffectsSensorDto.INSTALLING,
                        "The agent has not started the sensor yet.",
                        hooks,
                        dropped,
                        Map.of(),
                        toggle);
            }
            String state = AgentBridgeAccess.text(sensor, "state");
            String disabled = AgentBridgeAccess.text(counters, "disabledReason");
            if (state != null && state.startsWith("self-test-failed")) {
                String error = AgentBridgeAccess.text(sensor, "selfTestError");
                return new SideEffectsCoverage(
                        SideEffectsSensorDto.SELF_TEST_FAILED,
                        "The sensor failed its self-test and the agent removed it"
                                + (error == null ? "." : ": " + error),
                        hooks,
                        dropped,
                        Map.of(),
                        toggle);
            }
            if (disabled != null || "failed".equals(state) || "release-failed".equals(state)) {
                return new SideEffectsCoverage(
                        SideEffectsSensorDto.DISABLED,
                        "The agent disabled the sensor: " + (disabled == null ? state : disabled),
                        hooks,
                        dropped,
                        Map.of(),
                        toggle);
            }
            if (!INSTALLED.equals(state) || !AgentBridgeAccess.flag(counters, "active")) {
                return new SideEffectsCoverage(
                        SideEffectsSensorDto.INSTALLING,
                        "The sensor is " + (state == null ? "not installed yet" : state) + ".",
                        hooks,
                        dropped,
                        Map.of(),
                        toggle);
            }
            Map<String, Long> buckets = new LinkedHashMap<>();
            AgentBridgeAccess.map(counters, "buckets").forEach((bucket, value) -> {
                if (value instanceof Number number) {
                    buckets.put(bucket, number.longValue());
                }
            });
            return new SideEffectsCoverage(SideEffectsSensorDto.RECORDING, null, hooks, dropped, buckets, toggle);
        } catch (RuntimeException ex) {
            return new SideEffectsCoverage(
                    SideEffectsSensorDto.UNAVAILABLE, SIDE_EFFECTS_REQUIREMENT + ".", List.of(), 0L);
        }
    }

    /**
     * A Side Effects sensor's coverage.
     *
     * @param state a {@link SideEffectsSensorDto} state
     * @param reason why it does not record, or {@code null}
     * @param hooks its hooks
     * @param dropped records the bridge dropped for it because its ring was full
     * @param buckets for the files sensor, the operations the bridge counted in buckets rather than recorded, by bucket
     *     ({@code classFiles}, {@code archives}, {@code archiveFileSystems}, {@code javaHome},
     *     {@code classPathDirectories}), since the claim; empty otherwise
     * @param toggle the sensor's runtime switch, for an opt-in sensor while the agent is armed for this application;
     *     otherwise {@code null}
     */
    public record SideEffectsCoverage(
            String state,
            String reason,
            List<SideEffectsHookDto> hooks,
            long dropped,
            Map<String, Long> buckets,
            JavaAgentSensorToggleDto toggle) {

        public SideEffectsCoverage {
            hooks = hooks == null ? List.of() : List.copyOf(hooks);
            buckets = buckets == null ? Map.of() : Map.copyOf(buckets);
        }

        /** A coverage without a runtime switch. */
        public SideEffectsCoverage(
                String state, String reason, List<SideEffectsHookDto> hooks, long dropped, Map<String, Long> buckets) {
            this(state, reason, hooks, dropped, buckets, null);
        }

        /** A coverage without buckets or a runtime switch. */
        public SideEffectsCoverage(String state, String reason, List<SideEffectsHookDto> hooks, long dropped) {
            this(state, reason, hooks, dropped, Map.of(), null);
        }
    }

    /**
     * Why sensor {@code id} does not record for this application, starting with {@code requirement}, or {@code null}
     * when the agent is armed for it, the sensor is installed, and the bridge's counters under {@code countersKey}
     * carry no {@code disabledReason}.
     */
    private String sensorUnavailableReason(
            Map<String, Object> status, String requirement, String id, String countersKey, String disabledWhat) {
        Resolution resolution = resolve(status, AgentBridgeAccess.map(status, "claim"));
        if (!JavaAgentReport.ARMED.equals(resolution.state())) {
            String reason =
                    resolution.reason() == null ? "the agent is not armed for this application." : resolution.reason();
            if (JavaAgentReport.NOT_ATTACHED.equals(resolution.state())) {
                reason += " Start the application with -javaagent:bootui-agent.jar (see the Java Agent panel).";
            }
            return requirement + ": " + reason;
        }
        AgentClaim ours = claim.get();
        if (ours != null && !ours.uses(id)) {
            return requirement + ": this application's claim does not use it; bootui.agent.sensors must include " + id
                    + ".";
        }
        Map<String, Object> sensor = sensor(AgentBridgeAccess.map(status, "agent"), id);
        if (sensor == null) {
            return requirement + ": the agent did not start it; bootui.agent.sensors must include " + id + ".";
        }
        if (Boolean.FALSE.equals(sensor.get("active"))) {
            return requirement + ": the agent reports it inactive for this application's claim.";
        }
        String state = AgentBridgeAccess.text(sensor, "state");
        String disabled = AgentBridgeAccess.text(AgentBridgeAccess.map(status, countersKey), "disabledReason");
        if (AgentSensorSettings.EXECUTORS.equals(id)) {
            if ("testing".equals(state)) {
                return requirement + ": the executors sensor's core hooks have not passed their self-test yet.";
            }
            if (disabled != null) {
                return requirement + ": the agent disabled " + disabledWhat + ": " + disabled;
            }
            if (INSTALLED.equals(state) && !Boolean.TRUE.equals(sensor.get("selfTestPassed"))) {
                return requirement + ": the executors sensor's core hooks have not passed their self-test yet.";
            }
        }
        if (!INSTALLED.equals(state)) {
            return requirement + ": the sensor is " + (state == null ? "not installed" : state) + ".";
        }
        if (disabled != null) {
            return requirement + ": the agent disabled " + disabledWhat + ": " + disabled;
        }
        return null;
    }

    static Map<String, Object> sensor(Map<String, Object> agent, String id) {
        for (Object item : AgentBridgeAccess.items(agent, "sensors")) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> sensor = AgentBridgeAccess.map(Map.of("sensor", raw), "sensor");
                if (id.equals(AgentBridgeAccess.text(sensor, "id"))) {
                    return sensor;
                }
            }
        }
        return null;
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

    /** The sensors this application's armed claim uses, or none when it is not armed. */
    private List<String> claimedSensors(Resolution resolution) {
        AgentClaim ours = claim.get();
        if (ours == null || !JavaAgentReport.ARMED.equals(resolution.state())) {
            return List.of();
        }
        return ours.activeSensors();
    }

    /**
     * Switches the opt-in sensor {@code id} on or off at run time for this application ({@code docs/PLAN-v2.md} M5-14),
     * through its armed claim: the agent installs or removes it now, and the switch holds for this application's later
     * claims in this JVM, across DevTools restarts and Quarkus live reloads, never written anywhere. Returns the report
     * after the switch.
     *
     * @throws IllegalArgumentException when {@code id} is not one of {@link AgentSensorSettings#OPT_IN_SENSORS}
     * @throws IllegalStateException when the agent is not armed for this application, predates runtime switches, or
     *     refused or failed the switch
     */
    public JavaAgentReport switchSensor(String id, boolean enabled) {
        String sensor = id == null ? "" : id.trim();
        if (!AgentSensorSettings.OPT_IN_SENSORS.contains(sensor)) {
            throw new IllegalArgumentException(
                    "'" + id + "' is not an opt-in sensor: the sensors switched at run time are "
                            + String.join(", ", AgentSensorSettings.OPT_IN_SENSORS) + ".");
        }
        Map<String, Object> status = access.status();
        Resolution resolution = resolve(status, AgentBridgeAccess.map(status, "claim"));
        AgentClaim ours = claim.get();
        if (!JavaAgentReport.ARMED.equals(resolution.state()) || ours == null) {
            String reason = resolution.reason() == null ? "the agent is not armed for it." : resolution.reason();
            throw new IllegalStateException(
                    "The BootUI agent is not armed for this application, so its sensors cannot be switched: " + reason);
        }
        if (!access.sensorSwitchSupported()) {
            throw new IllegalStateException(switchUnsupportedReason());
        }
        JavaAgentSensorToggleDto current = toggle(ours, sensor(AgentBridgeAccess.map(status, "agent"), sensor), sensor);
        if (enabled && !current.available()) {
            throw new IllegalStateException(current.unavailableReason());
        }
        long revision = ours.sensorsRevision();
        Map<String, Object> answer = ours.switchSensor(sensor, enabled);
        String answered = String.valueOf(answer.get("status"));
        // The bridge commits a switch before it calls the agent: one the agent then failed still holds, for this run
        // and
        // the next claims, so the report says so, with the agent's state and message, rather than a refusal.
        boolean committed =
                AgentClaim.FAILED.equals(answered) && ours.sensorsRevision() > revision && ours.uses(sensor) == enabled;
        if (committed) {
            switchFailures.put(
                    sensor,
                    new SwitchFailure(ours.generation(), ours.sensorsRevision(), reason(answer, "no reason given")));
        } else if (AgentClaim.ARMED.equals(answered)) {
            switchFailures.remove(sensor);
        }
        if (AgentClaim.ARMED.equals(answered) || committed) {
            for (java.util.function.Consumer<String> listener : switchListeners) {
                try {
                    listener.accept(sensor);
                } catch (RuntimeException ex) {
                    // A listener only starts routing early; the next read starts it anyway.
                }
            }
            return report();
        }
        String reason = reason(answer, "no reason given");
        if (AgentClaim.STALE.equals(answered)) {
            throw new IllegalStateException("This application's claim on the BootUI agent changed while switching "
                    + sensor + " (" + reason + "): reload and try again.");
        }
        throw new IllegalStateException("The BootUI agent could not switch " + sensor + ": " + reason);
    }

    /**
     * Runs {@code listener} with the sensor's id after each runtime switch the bridge kept, such as Side Effects marking
     * the run as switched for that sensor (M5-7b's comparison leaves it out) and starting to route its records when the
     * claim used no side-effect sensor until then.
     */
    public void onSensorSwitched(java.util.function.Consumer<String> listener) {
        if (listener != null) {
            switchListeners.add(listener);
        }
    }

    private String switchUnsupportedReason() {
        return "The attached BootUI agent predates runtime sensor switches: attach the bootui-agent jar of BootUI "
                + settings.bootUiVersion() + ".";
    }

    /** The opt-in sensors' runtime switches while this application's claim is armed, or none. */
    private List<JavaAgentSensorToggleDto> toggles(Resolution resolution, Map<String, Object> agent) {
        AgentClaim ours = claim.get();
        if (ours == null || !JavaAgentReport.ARMED.equals(resolution.state())) {
            return List.of();
        }
        List<JavaAgentSensorToggleDto> toggles = new ArrayList<>();
        for (String id : AgentSensorSettings.OPT_IN_SENSORS) {
            toggles.add(toggle(ours, sensor(agent, id), id));
        }
        return toggles;
    }

    /**
     * The runtime switch of {@code id} for this application's claim, or {@code null} when {@code id} is not an opt-in
     * sensor; {@code sensor} is the agent's status row for it, or {@code null}.
     */
    private JavaAgentSensorToggleDto toggle(AgentClaim ours, Map<String, Object> sensor, String id) {
        if (!AgentSensorSettings.OPT_IN_SENSORS.contains(id)) {
            return null;
        }
        boolean configured = ours.sensors().sensors().contains(id);
        boolean enabled = ours.uses(id);
        String reported = sensor == null ? null : AgentBridgeAccess.text(sensor, "state");
        String state;
        if (!enabled) {
            state = "off";
        } else {
            // A side-effect sensor reads released until the shared transformer is reinstalled with it.
            state = reported == null || "released".equals(reported) ? "installing" : reported;
        }
        String unavailable = access.sensorSwitchSupported() ? null : switchUnsupportedReason();
        if (unavailable == null
                && AgentSensorSettings.THREAD_ACTIVITY.equals(id)
                && !access.threadActivitySupported()) {
            unavailable =
                    "The attached BootUI agent predates the thread-activity sensor: attach the bootui-agent jar of"
                            + " BootUI " + settings.bootUiVersion() + ".";
        }
        if (unavailable == null && AgentSensorSettings.RESOURCES.equals(id) && !access.resourcesSupported()) {
            unavailable = "The attached BootUI agent predates the resources sensor: attach the bootui-agent jar of"
                    + " BootUI " + settings.bootUiVersion() + ".";
        }
        if (unavailable == null && !enabled && AgentSensorSettings.THREADS.equals(id) && threadsFailedThisRun(ours)) {
            unavailable = "The threads sensor failed in this run: it stays off until the application restarts.";
        }
        if (unavailable == null
                && !enabled
                && !AgentSensorSettings.THREADS.equals(id)
                && reported != null
                && reported.startsWith("self-test-failed")) {
            // A side-effect sensor that failed its self-test stays out of the transformer for the JVM's life.
            unavailable =
                    "The " + id + " sensor failed its self-test in this JVM: it stays off until the JVM restarts.";
        }
        SwitchFailure failed = switchFailures.get(id);
        String failure = failed != null
                        && failed.generation().equals(ours.generation())
                        && failed.revision() == ours.sensorsRevision()
                        && !INSTALLED.equals(reported)
                ? failed.reason()
                : null;
        if (failure != null && enabled) {
            state = "failed";
        }
        return new JavaAgentSensorToggleDto(
                id,
                configured,
                enabled,
                ours.sensorOverrides().containsKey(id),
                state,
                AgentSensorSettings.optInReason(id),
                unavailable == null,
                unavailable,
                failure == null ? null : "The agent failed this switch: " + failure);
    }

    /** The agent's failure of a committed switch: the claim's generation and switch revision, and the reason. */
    private record SwitchFailure(Long generation, long revision, String reason) {}

    /** Whether the bridge disabled the threads sensor for this claim's generation, or for every generation. */
    private boolean threadsFailedThisRun(AgentClaim ours) {
        Long disabled = AgentBridgeAccess.number(
                AgentBridgeAccess.map(access.status(), AgentSensorSettings.THREADS), "disabledGeneration");
        return disabled != null && (disabled == Long.MAX_VALUE || disabled.equals(ours.generation()));
    }

    /**
     * Each sensor the agent reports, with the bridge's counters for it, which the bridge keeps under the sensor's id.
     * A sensor is active only when this application's armed claim lists it and the agent does not report it inactive.
     */
    private static List<JavaAgentSensorDto> sensors(
            Map<String, Object> agent, Map<String, Object> status, List<String> claimed) {
        List<JavaAgentSensorDto> sensors = new ArrayList<>();
        for (Object item : AgentBridgeAccess.items(agent, "sensors")) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> sensor = AgentBridgeAccess.map(Map.of("sensor", raw), "sensor");
                Long types = AgentBridgeAccess.number(sensor, "instrumentedTypes");
                if (types == null) {
                    types = AgentBridgeAccess.number(sensor, "transformed");
                }
                String id = AgentBridgeAccess.text(sensor, "id");
                Map<String, Object> counters = id == null ? Map.of() : AgentBridgeAccess.map(status, id);
                boolean inventory = AgentSensorSettings.INVENTORY.equals(id);
                boolean codePaths = AgentSensorSettings.CODE_PATHS.equals(id);
                boolean caught = AgentSensorSettings.CAUGHT_EXCEPTIONS.equals(id);
                sensors.add(new JavaAgentSensorDto(
                        id,
                        AgentBridgeAccess.text(sensor, "state"),
                        id != null && claimed.contains(id) && !Boolean.FALSE.equals(sensor.get("active")),
                        types == null ? 0 : types.intValue(),
                        strings(AgentBridgeAccess.items(sensor, "failures")),
                        millis(sensor, "durationMillis"),
                        millis(sensor, "installMillis"),
                        millis(sensor, "selfTestMillis"),
                        longValue(sensor, "retransformMillis"),
                        AgentBridgeAccess.flag(sensor, "selfTestPassed"),
                        AgentBridgeAccess.text(sensor, "selfTestError"),
                        texts(AgentBridgeAccess.map(sensor, "selfTestSteps")),
                        hooks(sensor, counters),
                        count(sensor, "failed"),
                        count(sensor, "skipped"),
                        count(sensor, "transformed"),
                        count(sensor, "retransformed"),
                        counters.isEmpty() || inventory || codePaths || caught ? null : executorCounters(counters),
                        counters.isEmpty() || !inventory ? null : inventoryCounters(counters),
                        counters.isEmpty() || !codePaths ? null : codePathsCounters(counters)));
            }
        }
        return sensors;
    }

    /**
     * A sensor's hooks, each with how often it fired from the bridge's counters for the sensor: {@code keyed} and
     * {@code applied} for the propagation sensors' {@code key} and {@code apply} hooks, {@code recorded} for the
     * inventory sensor's {@code record} hooks.
     */
    private static List<JavaAgentHookDto> hooks(Map<String, Object> sensor, Map<String, Object> counters) {
        Map<String, Object> keyed = AgentBridgeAccess.map(counters, "keyed");
        Map<String, Object> applied = AgentBridgeAccess.map(counters, "applied");
        Map<String, Object> recorded = AgentBridgeAccess.map(counters, "recorded");
        List<JavaAgentHookDto> hooks = new ArrayList<>();
        for (Object item : AgentBridgeAccess.items(sensor, "hooks")) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> hook = AgentBridgeAccess.map(Map.of("hook", raw), "hook");
                String id = AgentBridgeAccess.text(hook, "id");
                String kind = AgentBridgeAccess.text(hook, "kind");
                Map<String, Object> firings =
                        switch (kind == null ? "" : kind) {
                            case "apply" -> applied;
                            case "record" -> recorded;
                            default -> keyed;
                        };
                Long fired = AgentBridgeAccess.number(firings, id);
                hooks.add(new JavaAgentHookDto(
                        id,
                        kind,
                        AgentBridgeAccess.text(hook, "type"),
                        AgentBridgeAccess.flag(hook, "present"),
                        AgentBridgeAccess.flag(hook, "transformed"),
                        AgentBridgeAccess.text(hook, "selfTest"),
                        fired == null ? 0L : fired));
            }
        }
        return hooks;
    }

    private static JavaAgentExecutorCountersDto executorCounters(Map<String, Object> executors) {
        return new JavaAgentExecutorCountersDto(
                longValue(executors, "pending"),
                longValue(executors, "neverApplied"),
                longValue(executors, "ambiguous"),
                longValue(executors, "stale"),
                longValue(executors, "refused"),
                longValue(executors, "virtualSkipped"),
                longValue(executors, "periodicSkipped"),
                longValue(executors, "skippedTasks"),
                longValue(executors, "skippedThreads"),
                longValue(executors, "failures"),
                AgentBridgeAccess.text(executors, "disabledReason"),
                AgentBridgeAccess.flag(executors, "asyncApplies"),
                AgentBridgeAccess.number(executors, "libraryThreadsSkipped"),
                AgentBridgeAccess.number(executors, "poolWorkersSkipped"));
    }

    private static JavaAgentInventoryCountersDto inventoryCounters(Map<String, Object> inventory) {
        return new JavaAgentInventoryCountersDto(
                longValue(inventory, "methodsTracked"),
                longValue(inventory, "executedThisRun"),
                longValue(inventory, "methodOverflow"),
                longValue(inventory, "transformFailures"),
                longValue(inventory, "codeSources"),
                longValue(inventory, "ringDropped"),
                longValue(inventory, "ringLost"),
                longValue(inventory, "internOverflow"),
                AgentBridgeAccess.text(inventory, "disabledReason"));
    }

    private static JavaAgentCodePathsCountersDto codePathsCounters(Map<String, Object> codePaths) {
        return new JavaAgentCodePathsCountersDto(
                longValue(codePaths, "fragmentsFlushed"),
                longValue(codePaths, "fragmentsDropped"),
                longValue(codePaths, "queueDropped"),
                longValue(codePaths, "callsDropped"),
                longValue(codePaths, "queueBytes"),
                longValue(codePaths, "excludedMethods"),
                longValue(codePaths, "errors"),
                AgentBridgeAccess.text(codePaths, "disabledReason"));
    }

    private static Map<String, String> texts(Map<String, Object> map) {
        Map<String, String> texts = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (value != null) {
                texts.put(key, String.valueOf(value));
            }
        });
        return texts;
    }

    /** Whether the hooks were verified on this JDK's feature release; others rely on the self-test alone. */
    static boolean verifiedJdk(int feature) {
        return VERIFIED_JDKS.contains(feature);
    }

    /**
     * The production sensors' cumulative transformation cost, summed across them, or {@code null} when the agent
     * reports no sensor. Never the agent's test-only diagnostic installer.
     */
    private static JavaAgentRetransformationDto retransformation(Map<String, Object> agent) {
        int transformed = 0;
        int retransformed = 0;
        int failed = 0;
        int skipped = 0;
        long millis = 0;
        boolean running = false;
        boolean failure = false;
        boolean installed = false;
        boolean any = false;
        for (Object item : AgentBridgeAccess.items(agent, "sensors")) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> sensor = AgentBridgeAccess.map(Map.of("sensor", raw), "sensor");
                any = true;
                transformed = saturated(transformed, count(sensor, "transformed"));
                retransformed = saturated(retransformed, count(sensor, "retransformed"));
                failed = saturated(failed, count(sensor, "failed"));
                skipped = saturated(skipped, count(sensor, "skipped"));
                millis += longValue(sensor, "retransformMillis");
                String state = AgentBridgeAccess.text(sensor, "state");
                running |= "installing".equals(state);
                failure |= state != null && state.contains("failed");
                installed |= INSTALLED.equals(state) || "testing".equals(state);
            }
        }
        if (!any) {
            return null;
        }
        String state = running ? "installing" : failure ? "failed" : installed ? INSTALLED : "off";
        return new JavaAgentRetransformationDto(state, transformed, retransformed, failed, skipped, millis, running);
    }

    private static int saturated(int total, int value) {
        return (int) Math.min(Integer.MAX_VALUE, (long) total + value);
    }

    private static Long millis(Map<String, Object> map, String key) {
        Long value = AgentBridgeAccess.number(map, key);
        return value == null || value < 0 ? null : value;
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
