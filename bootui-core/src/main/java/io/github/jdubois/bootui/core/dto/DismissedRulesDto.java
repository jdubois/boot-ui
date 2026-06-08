package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The set of advisor rule IDs that have been dismissed by the developer.
 */
public record DismissedRulesDto(List<String> dismissed) {}
