package io.github.jdubois.bootui.core.dto;

/**
 * Result of writing both GraalVM artifacts — the {@code reachability-metadata.json} scaffold and the
 * tailored {@code Dockerfile-native} — into the host application's source tree in a single action.
 *
 * <p>{@code installed} is {@code true} only when both files were written. {@code status} is the most
 * severe of the two individual outcomes (one of {@code WRITTEN}, {@code EXISTS}, {@code UNAVAILABLE},
 * or {@code ERROR}) and drives the overall alert styling, while {@code metadata} and {@code dockerfile}
 * carry each file's own outcome so the panel can report them line by line.
 */
public record GraalVmInstallAllResultDto(
        boolean installed,
        String status,
        String message,
        GraalVmInstallResultDto metadata,
        GraalVmInstallResultDto dockerfile) {}
