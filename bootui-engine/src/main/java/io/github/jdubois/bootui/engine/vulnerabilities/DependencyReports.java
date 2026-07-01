package io.github.jdubois.bootui.engine.vulnerabilities;

import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyScanStatusDto;
import io.github.jdubois.bootui.core.dto.DependencySeverityCountDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DependencyReports {

    private static final List<String> VULNERABILITY_SEVERITIES =
            List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN");

    private static final Comparator<String> ALPHABETIC =
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

    private static final Comparator<DependencyDto> DEPENDENCY_ORDER = Comparator.comparingInt(
                    (DependencyDto dependency) -> severityRank(dependency.highestSeverity()))
            .thenComparing(DependencyDto::packageName, ALPHABETIC)
            .thenComparing(DependencyDto::version, ALPHABETIC);

    public static final Comparator<DependencyVulnerabilityDto> VULNERABILITY_ORDER = Comparator.comparingInt(
                    (DependencyVulnerabilityDto v) -> severityRank(v.severity()))
            .thenComparing(DependencyVulnerabilityDto::id);

    private DependencyReports() {}

    public static DependenciesReport report(
            boolean scanningEnabled,
            String status,
            String message,
            Long scannedAt,
            int packagesScanned,
            List<DependencyDto> dependencies) {
        List<DependencyDto> orderedDependencies =
                dependencies.stream().sorted(DEPENDENCY_ORDER).toList();
        int vulnerabilitiesFound = orderedDependencies.stream()
                .mapToInt(DependencyDto::vulnerabilityCount)
                .sum();
        return new DependenciesReport(
                scanningEnabled,
                orderedDependencies.size(),
                (int) orderedDependencies.stream()
                        .filter(dependency -> dependency.vulnerabilityCount() > 0)
                        .count(),
                severityCounts(orderedDependencies),
                new DependencyScanStatusDto(
                        "OSV.dev", status, message, scannedAt, packagesScanned, vulnerabilitiesFound),
                orderedDependencies);
    }

    public static List<DependencySeverityCountDto> severityCounts(List<DependencyDto> dependencies) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : VULNERABILITY_SEVERITIES) {
            counts.put(severity, 0);
        }
        for (DependencyDto dependency : dependencies) {
            for (DependencyVulnerabilityDto vulnerability : dependency.vulnerabilities()) {
                counts.computeIfPresent(vulnerability.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new DependencySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static String highestSeverity(List<DependencyVulnerabilityDto> vulnerabilities) {
        return vulnerabilities.stream()
                .map(DependencyVulnerabilityDto::severity)
                .min(Comparator.comparingInt(DependencyReports::severityRank))
                .orElse("NONE");
    }

    public static int severityRank(String severity) {
        if (severity == null) {
            return 6;
        }
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            case "UNKNOWN" -> 4;
            case "NONE" -> 5;
            default -> 6;
        };
    }

    public static String normalizeSeverity(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (VULNERABILITY_SEVERITIES.contains(normalized)) {
            return normalized;
        }
        return "UNKNOWN";
    }

    public static String normalizeSeverity(double score) {
        if (score >= 9.0d) {
            return "CRITICAL";
        }
        if (score >= 7.0d) {
            return "HIGH";
        }
        if (score >= 4.0d) {
            return "MEDIUM";
        }
        return "LOW";
    }

    public static Double parseScore(String value) {
        if (value == null || value.startsWith("CVSS:")) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
