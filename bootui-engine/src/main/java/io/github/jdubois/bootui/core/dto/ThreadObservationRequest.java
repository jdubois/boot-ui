package io.github.jdubois.bootui.core.dto;

/** Optional per-investigation settings; absent values use the server's bounded defaults. */
public record ThreadObservationRequest(Integer samples, Long intervalMillis) {}
