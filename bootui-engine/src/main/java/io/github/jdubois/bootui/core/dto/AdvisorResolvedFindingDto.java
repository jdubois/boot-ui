package io.github.jdubois.bootui.core.dto;

/**
 * A finding of the previous scan that this scan no longer reports.
 *
 * @param ruleId the rule that reported it
 * @param text the finding text from the previous scan, already sanitized
 */
public record AdvisorResolvedFindingDto(String ruleId, String text) {

    private static final int MAX_TEXT_LENGTH = 240;

    public AdvisorResolvedFindingDto {
        text = text == null ? "" : text.substring(0, Math.min(text.length(), MAX_TEXT_LENGTH));
    }
}
