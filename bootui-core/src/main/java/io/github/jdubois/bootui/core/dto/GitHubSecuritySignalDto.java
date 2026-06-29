package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Permission-aware security signal summary from GitHub. Code scanning and secret scanning expose
 * counts only; Dependabot may additionally carry a bounded list of non-secret alert details.
 */
public record GitHubSecuritySignalDto(
        String label, String status, Integer count, String unavailableReason, List<GitHubDependabotAlertDto> alerts) {

    public GitHubSecuritySignalDto(String label, String status, Integer count, String unavailableReason) {
        this(label, status, count, unavailableReason, List.of());
    }
}
