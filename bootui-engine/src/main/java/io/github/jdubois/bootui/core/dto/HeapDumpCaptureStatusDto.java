package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about the most recent heap dump capture or live-heap analysis action.
 */
public record HeapDumpCaptureStatusDto(String status, String message, Long capturedAtEpochMs) {}
