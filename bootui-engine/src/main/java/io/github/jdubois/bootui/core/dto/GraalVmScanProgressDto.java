package io.github.jdubois.bootui.core.dto;

/** Lightweight progress snapshot for a running GraalVM readiness scan. */
public record GraalVmScanProgressDto(
        boolean running, String phase, String message, int dependenciesScanned, int dependenciesTotal) {}
