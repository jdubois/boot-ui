package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Full detail for a single grouped exception: its summary, representative stack trace, cause chain,
 * and the most recent occurrences.
 */
public record ExceptionDetailDto(
        ExceptionGroupDto group,
        List<ExceptionFrameDto> frames,
        List<ExceptionCauseDto> causes,
        List<ExceptionOccurrenceDto> occurrences) {}
