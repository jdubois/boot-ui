package io.github.jdubois.bootui.core.dto;

/**
 * Per-minute token usage bucket.
 */
public record AiTokenBucketDto(long epochMinute, long inputTokens, long outputTokens, int callCount) {}
