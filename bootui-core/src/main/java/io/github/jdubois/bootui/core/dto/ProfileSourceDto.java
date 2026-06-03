package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Properties contributed by a single profile-specific property source.
 */
public record ProfileSourceDto(String sourceName, String profile, List<ConfigPropertyDto> properties) {}
