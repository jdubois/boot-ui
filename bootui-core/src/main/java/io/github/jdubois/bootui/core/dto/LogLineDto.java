package io.github.jdubois.bootui.core.dto;

/**
 * A single log line for the live log tail.
 */
public record LogLineDto(long timestamp, String level, String logger, String message, String thread) {}
