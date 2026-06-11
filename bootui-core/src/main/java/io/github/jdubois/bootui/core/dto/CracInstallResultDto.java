package io.github.jdubois.bootui.core.dto;

/**
 * Result of writing a generated CRaC container asset (the {@code Dockerfile-crac} or its
 * {@code checkpoint-and-run.sh} entrypoint) into the host application's project directory.
 *
 * <p>{@code status} is one of {@code WRITTEN}, {@code EXISTS} (an existing non-generated file was
 * left untouched), {@code UNAVAILABLE} (the application is not running from an exploded source
 * tree), or {@code ERROR}. {@code path} is the project-relative location, when known.
 */
public record CracInstallResultDto(boolean installed, String status, String message, String path) {}
