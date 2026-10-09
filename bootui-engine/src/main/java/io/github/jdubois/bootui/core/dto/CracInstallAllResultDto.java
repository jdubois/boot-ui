package io.github.jdubois.bootui.core.dto;

/**
 * Result of writing both CRaC container assets — the tailored {@code Dockerfile-crac} and its
 * {@code checkpoint-and-run.sh} entrypoint — into the host application's project directory in a
 * single action.
 *
 * <p>{@code installed} is {@code true} only when both files were written. {@code status} is the most
 * severe of the two individual outcomes (one of {@code WRITTEN}, {@code EXISTS}, {@code UNAVAILABLE},
 * or {@code ERROR}) and drives the overall alert styling, while {@code dockerfile} and
 * {@code entrypoint} carry each file's own outcome so the panel can report them line by line.
 */
public record CracInstallAllResultDto(
        boolean installed,
        String status,
        String message,
        CracInstallResultDto dockerfile,
        CracInstallResultDto entrypoint) {}
