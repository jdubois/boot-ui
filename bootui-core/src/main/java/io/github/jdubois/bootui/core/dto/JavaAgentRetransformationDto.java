package io.github.jdubois.bootui.core.dto;

/**
 * The cumulative class-transformation cost of the BootUI Java agent's sensors since the JVM started
 * ({@code docs/PLAN-v2.md} §5.13), summed across the sensors it reports. Transformers stay installed across claims
 * (D34), so these are JVM-wide totals rather than one claim's cost; each sensor row carries its own share.
 *
 * @param state {@code installing} while any sensor installs, {@code failed} when one failed, otherwise
 *     {@code installed}
 * @param transformed classes the sensors transformed as they loaded
 * @param retransformed already loaded classes the sensors retransformed
 * @param failed classes that failed to transform
 * @param skipped classes skipped (ignored or unmodifiable)
 * @param durationMillis the summed time the sensors spent in retransformation passes (installs, refinements, and
 *     releases). Sensors install one after another, so this is aggregate work, not a wall-clock interval
 * @param running whether a sensor is still installing
 */
public record JavaAgentRetransformationDto(
        String state,
        int transformed,
        int retransformed,
        int failed,
        int skipped,
        long durationMillis,
        boolean running) {}
