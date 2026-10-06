package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The BootUI Java agent's status for the Java Agent panel ({@code docs/PLAN-v2.md} §5.13): whether it is attached,
 * who holds its claim, its sensors, and how to attach it.
 *
 * @param state {@link #NOT_ATTACHED}, {@link #DORMANT}, {@link #ARMED}, {@link #HELD}, {@link #DISARMED},
 *     {@link #UNAVAILABLE}, {@link #FAILED}, or {@link #DISABLED}
 * @param reason why the agent is in that state, or {@code null} when nothing needs explaining
 * @param agentVersion the attached agent's version, or {@code null} when none is attached
 * @param bootUiVersion this BootUI's version
 * @param protocol the attached bridge's protocol, or {@code null} when none is attached
 * @param expectedProtocol the bridge protocol this BootUI speaks
 * @param jdk the running JDK
 * @param loadMode {@code javaagent} or {@code attach}, or {@code null} when none is attached
 * @param jarPath the attached agent's jar, or {@code null} when none is attached
 * @param startupMicros how long the agent's {@code premain} took, or {@code null} when unknown
 * @param claim the current claim in the JVM, this application's or another's, or {@code null} when none
 * @param heldBy the owner holding the agent when it is {@link #HELD}, otherwise {@code null}
 * @param sensors the agent's sensors, empty before the first sensor ships
 * @param toggles the runtime switches of the opt-in sensors, while this application's claim is {@link #ARMED}; empty
 *     otherwise
 * @param retransformation the last retransformation, or {@code null} when the agent never installed
 * @param counters the bridge's claim counters, or {@code null} when none is attached
 * @param messages the agent's recent messages, oldest first
 * @param warnings what BootUI found wrong with the attached agent, such as a version mismatch
 * @param setup the setup snippets
 */
public record JavaAgentReport(
        String state,
        String reason,
        String agentVersion,
        String bootUiVersion,
        Integer protocol,
        int expectedProtocol,
        String jdk,
        String loadMode,
        String jarPath,
        Long startupMicros,
        JavaAgentClaimDto claim,
        String heldBy,
        List<JavaAgentSensorDto> sensors,
        List<JavaAgentSensorToggleDto> toggles,
        JavaAgentRetransformationDto retransformation,
        JavaAgentCountersDto counters,
        List<String> messages,
        List<String> warnings,
        JavaAgentSetupDto setup) {

    /** No BootUI agent bridge is on the bootstrap class path: the JVM runs without {@code -javaagent}. */
    public static final String NOT_ATTACHED = "NOT_ATTACHED";

    /** The agent is attached but this application did not claim it, for example with {@code bootui.agent.enabled=false}. */
    public static final String DORMANT = "DORMANT";

    /** This application's claim is armed. */
    public static final String ARMED = "ARMED";

    /** Another application in this JVM holds the agent. */
    public static final String HELD = "HELD";

    /** This application's run ended its claim; the agent records nothing until the next claim. */
    public static final String DISARMED = "DISARMED";

    /** The bridge is present but the agent did not start, speaks another protocol, or cannot run here. */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** The agent failed this application's claim. */
    public static final String FAILED = "FAILED";

    /** BootUI's agent support is off here, such as in Quarkus production mode. */
    public static final String DISABLED = "DISABLED";

    public JavaAgentReport {
        sensors = DtoCollections.immutableCopy(sensors);
        toggles = DtoCollections.immutableCopy(toggles);
        messages = DtoCollections.immutableCopy(messages);
        warnings = DtoCollections.immutableCopy(warnings);
    }
}
