package io.github.jdubois.bootui.core.dto;

/**
 * Whether the Vulnerabilities panel can show each dependency's runtime reach ({@code docs/PLAN-v2.md} §5.15, M5-9a),
 * read from the BootUI agent's inventory sensor through Code Inventory.
 *
 * @param available whether rows carry a reach; without it they carry none
 * @param unavailableReason why not, such as the Java Agent panel's reason or the Code Inventory panel being disabled,
 *     or {@code null}
 * @param generation the agent claim generation (the run) the reach is read for, or {@code null}
 * @param note what reach does and does not mean, always said when available
 * @param incompleteReason why no dependency can be said not loaded in this run, such as the agent's class-load recorder
 *     not running, or {@code null}; every row that would otherwise read not loaded is then {@code UNKNOWN} with no
 *     reason of its own
 */
public record RuntimeReachSummaryDto(
        boolean available, String unavailableReason, Long generation, String note, String incompleteReason) {

    /** Reach cannot be read, with why. */
    public static RuntimeReachSummaryDto unavailable(String reason) {
        return new RuntimeReachSummaryDto(false, reason, null, null, null);
    }
}
