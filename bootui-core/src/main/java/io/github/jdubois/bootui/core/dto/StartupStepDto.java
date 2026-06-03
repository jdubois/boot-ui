package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record StartupStepDto(long id, Long parentId, String name, long durationMs, List<TagDto> tags) {}
