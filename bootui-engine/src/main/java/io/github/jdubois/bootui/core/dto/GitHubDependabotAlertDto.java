package io.github.jdubois.bootui.core.dto;

/**
 * Bounded, non-secret summary of a single open Dependabot alert. Only public security-advisory
 * metadata is exposed: no secret values or vulnerable code snippets.
 */
public record GitHubDependabotAlertDto(
        int number,
        String state,
        String packageName,
        String ecosystem,
        String manifestPath,
        String severity,
        String ghsaId,
        String cveId,
        String summary,
        String vulnerableVersionRange,
        String firstPatchedVersion,
        String htmlUrl,
        Long createdAt,
        Long updatedAt) {}
