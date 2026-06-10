package io.github.jdubois.bootui.core.dto;

/**
 * Result of writing the generated {@code reachability-metadata.json} scaffold into the host
 * application's source tree.
 *
 * <p>{@code status} is one of {@code WRITTEN}, {@code EXISTS} (an existing non-generated file was
 * left untouched), {@code UNAVAILABLE} (the application is not running from an exploded source
 * tree), or {@code ERROR}. {@code path} is the project-relative location, when known.
 */
public record GraalVmInstallResultDto(boolean installed, String status, String message, String path) {}
