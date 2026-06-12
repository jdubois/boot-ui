package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A link in an exception's cause chain ({@code Caused by: ...}).
 *
 * <p>{@code commonFrames} mirrors the number of trailing frames shared with the enclosing
 * exception, so the UI can render the familiar {@code ... N more} suffix.</p>
 */
public record ExceptionCauseDto(
        String exceptionClassName, String message, List<ExceptionFrameDto> frames, int commonFrames) {}
