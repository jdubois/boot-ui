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
import java.util.Set;

public final class DependencyReports {

    private static final List<String> VULNERABILITY_SEVERITIES =
            List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN");

    private static final Comparator<String> ALPHABETIC =
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

    private static final Comparator<DependencyDto> DEPENDENCY_ORDER = Comparator.comparingInt(
                    (DependencyDto dependency) -> severityRank(dependency.highestSeverity()))
            .thenComparing(DependencyDto::packageName, ALPHABETIC)
            .thenComparing(DependencyDto::version, ALPHABETIC);

    /**
     * Not-yet-reviewed vulnerabilities sort first (by severity, then id); dismissed vulnerabilities always
     * sink to the bottom of a dependency's list, mirroring how the other advisors keep dismissed findings
     * out of the way without hiding them.
     */
    public static final Comparator<DependencyVulnerabilityDto> VULNERABILITY_ORDER = Comparator.comparing(
                    DependencyVulnerabilityDto::dismissed)
            .thenComparingInt((DependencyVulnerabilityDto v) -> severityRank(v.severity()))
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

    /**
     * Tallies vulnerabilities by severity, excluding any marked {@link DependencyVulnerabilityDto#dismissed()}
     * &mdash; dismissed findings are kept in the report (so they can be restored) but no longer count
     * towards the severity breakdown, mirroring how the other advisors exclude dismissed rule violations
     * from their score/severity rollups.
     */
    public static List<DependencySeverityCountDto> severityCounts(List<DependencyDto> dependencies) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : VULNERABILITY_SEVERITIES) {
            counts.put(severity, 0);
        }
        for (DependencyDto dependency : dependencies) {
            for (DependencyVulnerabilityDto vulnerability : dependency.vulnerabilities()) {
                if (vulnerability.dismissed()) {
                    continue;
                }
                counts.computeIfPresent(vulnerability.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new DependencySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * The most severe <em>active</em> (non-dismissed) vulnerability in the list, or {@code "NONE"} if there
     * is none.
     */
    public static String highestSeverity(List<DependencyVulnerabilityDto> vulnerabilities) {
        return vulnerabilities.stream()
                .filter(vulnerability -> !vulnerability.dismissed())
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
        if ("MODERATE".equals(normalized)) {
            // OSV.dev's `database_specific.severity` field (sourced from the GitHub Security Advisory
            // database) always labels this tier "MODERATE" -- verified against 170 real advisories, which
            // used exactly {CRITICAL, HIGH, LOW, MODERATE} and never "MEDIUM". (GitHub's newer REST API
            // uses lowercase "medium" for the same tier via a different, unrelated channel.)
            return "MEDIUM";
        }
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

    /**
     * Parses an OSV {@code severity[].score} value into a numeric Base Score.
     *
     * <p>Per the <a href="https://ossf.github.io/osv-schema/">OSV schema</a>, {@code score} is a CVSS
     * vector string for {@code CVSS_V2}/{@code CVSS_V3}/{@code CVSS_V4} severity types (prefixed
     * {@code "CVSS:3.1/..."} etc. for v3+) &mdash; never a bare number for any of those types. Empirically,
     * real OSV.dev responses for the {@code CVSS_V3} type always carry the {@code "CVSS:3.0/"} or
     * {@code "CVSS:3.1/"} prefix, which this method dispatches to {@link CvssV3BaseScore}. Other CVSS
     * versions (v4.0, and the legacy unprefixed v2 format) are not computed &mdash; see
     * {@link CvssV3BaseScore}'s class Javadoc for why v4.0 is deliberately excluded; v2 was excluded because
     * zero occurrences appeared in a 170-advisory sample of real Maven-ecosystem OSV.dev responses. A bare
     * numeric string (not documented by the schema for any known type, but accepted defensively in case a
     * database ever emits one) still parses via {@link Double#parseDouble(String)}.
     *
     * @return the Base Score, or {@code null} if it can't be determined from {@code value}
     */
    public static Double parseScore(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("CVSS:3.0/") || value.startsWith("CVSS:3.1/")) {
            return CvssV3BaseScore.baseScore(value);
        }
        if (value.startsWith("CVSS:")) {
            // CVSS v4.0 (and any future major version): no closed-form Base Score equation exists; not
            // computed here. Falls through to null so callers keep the database_specific.severity label.
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The composite key used to persist a Vulnerabilities dismissal in the shared
     * {@code DismissedRulesStore} (the same flat {@code dismissedRules} list every advisor writes to,
     * distinguished by an id format each advisor owns). Deliberately keyed by vulnerability id + package
     * name and <em>not</em> the affected version, so a risk-acceptance dismissal survives a patch-version
     * bump of a dependency that is still vulnerable. The {@code "::"} delimiter is safe here because a
     * {@code packageName} is always exactly one {@code groupId:artifactId} (a single colon), so it can never
     * collide with the delimiter.
     */
    public static String dismissalKey(String vulnerabilityId, String packageName) {
        return vulnerabilityId + "::" + packageName;
    }

    /**
     * Returns a copy of {@code report} with each vulnerability's {@link DependencyVulnerabilityDto#dismissed()}
     * flag set from {@code dismissedIds}, and every count/ordering that depends on it (per-dependency
     * {@code vulnerabilityCount}/{@code highestSeverity}, and the report-level {@code vulnerable} count,
     * {@code severityCounts}, and {@code scan.vulnerabilitiesFound}) recomputed to exclude dismissed
     * vulnerabilities. Dismissed vulnerabilities are kept in each dependency's {@code vulnerabilities} list
     * (so they can be restored) but sink to the bottom via {@link #VULNERABILITY_ORDER}. Mirrors
     * {@code ArchitectureScanner.applyDismissals} and the equivalent method on every other advisor.
     *
     * @return {@code report} unchanged if it, or {@code dismissedIds}, is {@code null}/empty
     */
    public static DependenciesReport applyDismissals(DependenciesReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<DependencyDto> marked = report.dependencies().stream()
                .map(dependency -> markDismissals(dependency, dismissedIds))
                .toList();
        int vulnerable = (int) marked.stream()
                .filter(dependency -> dependency.vulnerabilityCount() > 0)
                .count();
        int vulnerabilitiesFound =
                marked.stream().mapToInt(DependencyDto::vulnerabilityCount).sum();
        DependencyScanStatusDto scan = report.scan();
        DependencyScanStatusDto updatedScan = scan == null
                ? null
                : new DependencyScanStatusDto(
                        scan.scanner(),
                        scan.status(),
                        scan.message(),
                        scan.scannedAt(),
                        scan.packagesScanned(),
                        vulnerabilitiesFound);
        return new DependenciesReport(
                report.scanningEnabled(), report.total(), vulnerable, severityCounts(marked), updatedScan, marked);
    }

    private static DependencyDto markDismissals(DependencyDto dependency, Set<String> dismissedIds) {
        List<DependencyVulnerabilityDto> markedVulnerabilities = dependency.vulnerabilities().stream()
                .map(vulnerability -> vulnerability.withDismissed(
                        dismissedIds.contains(dismissalKey(vulnerability.id(), dependency.packageName()))))
                .sorted(VULNERABILITY_ORDER)
                .toList();
        long activeCount =
                markedVulnerabilities.stream().filter(v -> !v.dismissed()).count();
        return new DependencyDto(
                dependency.groupId(),
                dependency.artifactId(),
                dependency.version(),
                dependency.packageName(),
                dependency.source(),
                (int) activeCount,
                highestSeverity(markedVulnerabilities),
                markedVulnerabilities);
    }
}
