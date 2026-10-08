package io.github.jdubois.bootui.core.dto;

/**
 * A single stack-trace frame surfaced by the Exceptions panel.
 *
 * <p>{@code applicationFrame} is true when the declaring class belongs to the host application
 * (as opposed to the JDK or a framework), so the UI can highlight the lines that matter.</p>
 */
public record ExceptionFrameDto(
        String declaringClass, String methodName, String fileName, Integer lineNumber, boolean applicationFrame) {}
