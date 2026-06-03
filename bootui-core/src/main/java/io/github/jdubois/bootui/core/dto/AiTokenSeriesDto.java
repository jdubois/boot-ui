package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Token usage time series payload.
 */
public record AiTokenSeriesDto(int minutes, List<AiTokenBucketDto> buckets) {}
