package io.github.jdubois.bootui.core.dto;

/**
 * Readiness signal for one third-party dependency on the classpath: whether it ships GraalVM
 * reachability metadata ({@code META-INF/native-image/}).
 */
public record GraalVmDependencyDto(String name, boolean shipsMetadata, String note) {}
