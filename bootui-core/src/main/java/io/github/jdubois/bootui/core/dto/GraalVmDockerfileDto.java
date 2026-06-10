package io.github.jdubois.bootui.core.dto;

/**
 * The generated multi-stage {@code Dockerfile-native} for the host application, tailored to its Maven
 * {@code artifactId}, together with whether it can be written directly into the project directory.
 *
 * <p>{@code content} is the full Dockerfile text. {@code installable} is {@code true} when the file can
 * be written to the project root (only possible when the app runs from an exploded build rather than a
 * packaged jar). {@code installPath} is the display-friendly, project-relative path it would be written
 * to, or the reason a direct write is unavailable when {@code installable} is {@code false}.
 */
public record GraalVmDockerfileDto(String content, boolean installable, String installPath) {}
