package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * How one child section of a request profile was correlated.
 *
 * @param type the section, named after its Live Activity entry type: {@code SQL}, {@code EXCEPTION},
 *     {@code SECURITY}, {@code REST_CLIENT}, or {@code CACHE}
 * @param available whether the section's source panel is enabled and capturing on this application
 * @param unavailableReason why the source is unavailable, or {@code null}
 * @param tier the weakest correlation tier used for a correlated child ({@code TRACE_ID},
 *     {@code SERVING_THREAD}, or {@code TIME_WINDOW}), or {@code null} when nothing was correlated
 * @param childTiers the tier that correlated each shown child, in the same order as the section's list in
 *     the profile
 * @param total the number of children correlated to the request, before the section bound
 * @param truncated the number of correlated children the section bound left out
 * @param ambiguous the number of children this request and at least one other captured request could
 *     equally claim, which are therefore attributed to neither
 */
public record RequestProfileSectionDto(
        String type,
        boolean available,
        String unavailableReason,
        String tier,
        List<String> childTiers,
        int total,
        int truncated,
        int ambiguous) {

    public RequestProfileSectionDto {
        childTiers = DtoCollections.immutableCopy(childTiers);
    }
}
