package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Side Effects sensor and its coverage ({@code docs/PLAN-v2.md} §5.16): its tab, whether this version ships it,
 * whether it records this run, and why not, with its hooks and counters.
 *
 * @param id the sensor's id, as {@code bootui.agent.sensors} names it, such as {@code processes}
 * @param group the tab it belongs to: {@code Network}, {@code Files and processes}, {@code Environment},
 *     {@code Threads and leaks}, {@code Blocking}, or {@code Security sinks}
 * @param label what it records, in a few words
 * @param state {@value #RECORDING}, {@value #INSTALLING}, {@value #SELF_TEST_FAILED}, {@value #DISABLED},
 *     {@value #NOT_CLAIMED}, {@value #UNAVAILABLE}, {@value #NOT_AVAILABLE}, or {@value #NOT_APPLICABLE}
 * @param reason why it does not record, or {@code null} while it records
 * @param rows the rows it holds for this run
 * @param occurrences the operations those rows count
 * @param dropped operations it saw that no row counts: the agent's transport was full, or this run's store was at its
 *     cap even for the Other row
 * @param hooks its hooks, empty for a sensor not in this version
 */
public record SideEffectsSensorDto(
        String id,
        String group,
        String label,
        String state,
        String reason,
        long rows,
        long occurrences,
        long dropped,
        List<SideEffectsHookDto> hooks) {

    /** The sensor records this run. */
    public static final String RECORDING = "recording";

    /** The agent is installing or self-testing its hooks. */
    public static final String INSTALLING = "installing";

    /** A hook failed its self-test: the agent removed the sensor. */
    public static final String SELF_TEST_FAILED = "self-test-failed";

    /** The agent disabled the sensor, as after too many internal errors. */
    public static final String DISABLED = "disabled";

    /** This application's {@code bootui.agent.sensors} does not ask for it. */
    public static final String NOT_CLAIMED = "not-claimed";

    /** The BootUI agent is not attached or not armed for this application. */
    public static final String UNAVAILABLE = "unavailable";

    /** This version of BootUI does not ship the sensor yet. */
    public static final String NOT_AVAILABLE = "not-available";

    /**
     * The sensor is installed but has nothing to watch on this stack, as {@code blocking} on Spring MVC, which runs no
     * event loop: its reason says why.
     */
    public static final String NOT_APPLICABLE = "not-applicable";

    public SideEffectsSensorDto {
        hooks = DtoCollections.immutableCopy(hooks);
    }
}
