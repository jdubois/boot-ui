package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Profile-aware view of the active configuration.
 */
public record ProfilesReport(List<String> activeProfiles, List<ProfileSourceDto> profileSources) {}
