package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Runtime Insights observation ({@code docs/PLAN-v2.md} §5.5): a measured fact about this run, never a cause, a
 * severity, or a fix.
 *
 * @param id a stable id, {@code kind:subject}, that survives refreshes and runs
 * @param kind the observation kind, such as {@code repeated-selects}
 * @param subject what it is about, such as {@code GET /api/orders/{id}}
 * @param status {@code OBSERVED}, {@code INSUFFICIENT} when the evidence is below the kind's minimum, or {@code PARTIAL}
 *     when a source it reads dropped events
 * @param sentence one sentence naming what was counted
 * @param eligible the requests that could have shown it
 * @param affected the requests that did
 * @param minimumTier the weakest correlation tier the observation accepts, such as {@code REQUEST_ID}
 * @param whatToCheck one to three conditional checks
 * @param exemplarRequestIds at most three request ids to open in Live Activity
 * @param evidenceRows the evidence rows its detail lists, at most 20
 * @param limitations what it cannot see
 */
public record RuntimeObservationDto(
        String id,
        String kind,
        String subject,
        String status,
        String sentence,
        long eligible,
        long affected,
        String minimumTier,
        List<String> whatToCheck,
        List<String> exemplarRequestIds,
        int evidenceRows,
        List<String> limitations) {

    public RuntimeObservationDto {
        whatToCheck = DtoCollections.immutableCopy(whatToCheck);
        exemplarRequestIds = DtoCollections.immutableCopy(exemplarRequestIds);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
