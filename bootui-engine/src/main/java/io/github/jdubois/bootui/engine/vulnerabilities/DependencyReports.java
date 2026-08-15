package io.github.jdubois.bootui.engine.vulnerabilities;

import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyScanStatusDto;
import io.github.jdubois.bootui.core.dto.DependencySeverityCountDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class DependencyReports {

    private static final List<String> VULNERABILITY_SEVERITIES =
            List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN", "NONE");

    /**
     * FIRST's public EPSS API documents a 2,000-character maximum for the comma-separated {@code cve}
     * parameter.
     */
    public static final int EPSS_CVE_PARAMETER_MAX_LENGTH = 2000;

    private static final Pattern CVE_ID = Pattern.compile("CVE-[0-9]{4}-[0-9]{4,}");

    private static final Comparator<String> ALPHABETIC =
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

    private static final Comparator<String> MAVEN_VERSION_ORDER = (left, right) -> {
        Integer compared = MavenVersionComparator.compare(left, right);
        return compared != null && compared != 0 ? compared : ALPHABETIC.compare(left, right);
    };

    private static final Comparator<DependencyDto> DEPENDENCY_ORDER = Comparator.comparingInt(
                    (DependencyDto dependency) -> severityRank(dependency.highestSeverity()))
            .thenComparing(DependencyDto::packageName, ALPHABETIC)
            .thenComparing(DependencyDto::version, MAVEN_VERSION_ORDER);

    /**
     * Not-yet-reviewed vulnerabilities sort first (by severity, then id); dismissed vulnerabilities always
     * sink to the bottom of a dependency's list, mirroring how the other advisors keep dismissed findings
     * out of the way without hiding them.
     */
    public static final Comparator<DependencyVulnerabilityDto> VULNERABILITY_ORDER = Comparator.comparing(
                    DependencyVulnerabilityDto::dismissed)
            .thenComparingInt((DependencyVulnerabilityDto v) -> severityRank(v.severity()))
            .thenComparing(DependencyVulnerabilityDto::id, ALPHABETIC);

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
        if (!Double.isFinite(score) || score < 0.0d || score > 10.0d) {
            return "UNKNOWN";
        }
        if (score >= 9.0d) {
            return "CRITICAL";
        }
        if (score >= 7.0d) {
            return "HIGH";
        }
        if (score >= 4.0d) {
            return "MEDIUM";
        }
        return score > 0.0d ? "LOW" : "NONE";
    }

    /**
     * Parses a supported OSV severity entry into a numeric Base Score.
     *
     * <p>Per the <a href="https://ossf.github.io/osv-schema/">OSV schema</a>, {@code type} identifies how
     * {@code score} must be interpreted. This method computes only {@code CVSS_V3} entries carrying a
     * prefixed CVSS v3.0/v3.1 vector via {@link CvssV3BaseScore}; CVSS v2/v4 and provider-specific score
     * types are left to the caller's database-specific severity fallback.
     *
     * @return the Base Score, or {@code null} if it can't be determined from {@code type}/{@code value}
     */
    public static Double parseScore(String type, String value) {
        if (!"CVSS_V3".equals(type) || value == null) {
            return null;
        }
        return CvssV3BaseScore.baseScore(value);
    }

    /**
     * Compatibility overload for callers that already know they have a {@code CVSS_V3} entry.
     */
    public static Double parseScore(String value) {
        return parseScore("CVSS_V3", value);
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
                .sorted(DEPENDENCY_ORDER)
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

    /**
     * Whether at least one of {@code fixedVersions} is newer than {@code currentVersion}, using the
     * lightweight {@link MavenVersionComparator} (BootUI takes no dependency on Maven's own
     * {@code ComparableVersion}). Backs {@link DependencyVulnerabilityDto#fixAvailable()}, which lets the UI
     * distinguish a newer reported fixed-version upgrade target from an advisory without one. A false
     * result means only that OSV reported no fixed event newer than the current version; it does not prove
     * that the current dependency is unaffected, because OSV already matched it as vulnerable and ranges may
     * be reintroduced or branch-specific.
     *
     * <p>An inconclusive per-version comparison (blank/unparseable input) is treated as "a fix is
     * available": OSV positively reported a {@code fixed} event for this advisory, so failing to parse the
     * version string is not grounds to hide that signal.
     *
     * @return {@code false} when {@code fixedVersions} is {@code null}/empty; otherwise {@code true} unless
     *     every entry can be positively confirmed to be no newer than {@code currentVersion}
     */
    public static boolean fixAvailable(String currentVersion, List<String> fixedVersions) {
        if (fixedVersions == null || fixedVersions.isEmpty()) {
            return false;
        }

        for (String fixedVersion : fixedVersions) {
            Integer compared = MavenVersionComparator.compare(currentVersion, fixedVersion);
            if (compared == null || compared < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first distinct package/version scan inputs, preserving inventory order and applying the
     * configured bound after de-duplication.
     */
    public static List<DependencyDto> scanCandidates(List<DependencyDto> dependencies, int maxPackages) {
        Map<String, DependencyDto> distinct = new LinkedHashMap<>();
        for (DependencyDto dependency : dependencies) {
            distinct.putIfAbsent(dependency.packageName() + ":" + dependency.version(), dependency);
        }
        return distinct.values().stream().limit(Math.max(1, maxPackages)).toList();
    }

    /** Returns the number of distinct package/version inputs in the inventory. */
    public static int scanCandidateCount(List<DependencyDto> dependencies) {
        Set<String> distinct = new LinkedHashSet<>();
        for (DependencyDto dependency : dependencies) {
            distinct.add(dependency.packageName() + ":" + dependency.version());
        }
        return distinct.size();
    }

    /**
     * Every distinct canonical CVE id referenced by any vulnerability across {@code dependencies},
     * preserving first-seen order. The advisory's own id is considered before its aliases because OSV
     * records may themselves be CVE records.
     */
    public static List<String> cveAliases(List<DependencyDto> dependencies) {
        Set<String> ids = new LinkedHashSet<>();
        for (DependencyDto dependency : dependencies) {
            for (DependencyVulnerabilityDto vulnerability : dependency.vulnerabilities()) {
                ids.addAll(cveIds(vulnerability));
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Splits canonical CVE ids into deterministic chunks whose comma-separated {@code cve} parameter never
     * exceeds FIRST.org's documented {@value #EPSS_CVE_PARAMETER_MAX_LENGTH}-character maximum.
     */
    public static List<List<String>> epssCveChunks(List<String> cveIds) {
        if (cveIds == null || cveIds.isEmpty()) {
            return List.of();
        }
        List<List<String>> chunks = new ArrayList<>();
        List<String> chunk = new ArrayList<>();
        int chunkLength = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String value : cveIds) {
            String cveId = canonicalCveId(value);
            if (cveId == null || !seen.add(cveId) || cveId.length() > EPSS_CVE_PARAMETER_MAX_LENGTH) {
                continue;
            }
            int addedLength = cveId.length() + (chunk.isEmpty() ? 0 : 1);
            if (!chunk.isEmpty() && chunkLength + addedLength > EPSS_CVE_PARAMETER_MAX_LENGTH) {
                chunks.add(List.copyOf(chunk));
                chunk.clear();
                chunkLength = 0;
                addedLength = cveId.length();
            }
            chunk.add(cveId);
            chunkLength += addedLength;
        }
        if (!chunk.isEmpty()) {
            chunks.add(List.copyOf(chunk));
        }
        return List.copyOf(chunks);
    }

    /**
     * Returns a canonical uppercase CVE id, or {@code null} for malformed input.
     */
    public static String canonicalCveId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return CVE_ID.matcher(normalized).matches() ? normalized : null;
    }

    /**
     * De-duplicates and orders reported Maven fixed-version candidates using Maven version semantics,
     * falling back to stable lexical ordering when comparison is inconclusive.
     */
    public static List<String> orderFixedVersions(Collection<String> versions, int limit) {
        if (versions == null || versions.isEmpty() || limit <= 0) {
            return List.of();
        }
        return versions.stream()
                .filter(version -> version != null && !version.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(MAVEN_VERSION_ORDER)
                .limit(limit)
                .toList();
    }

    /**
     * Returns a copy of {@code dependencies} with each vulnerability's {@code epssScore}/{@code
     * epssPercentile} set from the first of its {@code aliases} found in {@code epssByCve} &mdash; the
     * counterpart to {@link #cveAliases} on the write side of the same adapter-fetched EPSS lookup. A
     * vulnerability whose alias(es) have no entry in {@code epssByCve} (lookup disabled, failed, or the CVE
     * has no published EPSS score) is returned unchanged, i.e. with {@code null} EPSS fields.
     *
     * @return {@code dependencies} unchanged if {@code epssByCve} is {@code null}/empty
     */
    public static List<DependencyDto> applyEpssScores(
            List<DependencyDto> dependencies, Map<String, EpssScore> epssByCve) {
        if (epssByCve == null || epssByCve.isEmpty()) {
            return dependencies;
        }
        return dependencies.stream()
                .map(dependency -> applyEpssScores(dependency, epssByCve))
                .toList();
    }

    private static DependencyDto applyEpssScores(DependencyDto dependency, Map<String, EpssScore> epssByCve) {
        List<DependencyVulnerabilityDto> updated = dependency.vulnerabilities().stream()
                .map(vulnerability -> applyEpssScore(vulnerability, epssByCve))
                .toList();
        return new DependencyDto(
                dependency.groupId(),
                dependency.artifactId(),
                dependency.version(),
                dependency.packageName(),
                dependency.source(),
                dependency.vulnerabilityCount(),
                dependency.highestSeverity(),
                updated);
    }

    private static DependencyVulnerabilityDto applyEpssScore(
            DependencyVulnerabilityDto vulnerability, Map<String, EpssScore> epssByCve) {
        for (String cveId : cveIds(vulnerability)) {
            EpssScore epssScore = epssByCve.get(cveId);
            if (epssScore != null) {
                return vulnerability.withEpss(epssScore.probability(), epssScore.percentile());
            }
        }
        return vulnerability;
    }

    private static List<String> cveIds(DependencyVulnerabilityDto vulnerability) {
        Set<String> ids = new LinkedHashSet<>();
        String ownId = canonicalCveId(vulnerability.id());
        if (ownId != null) {
            ids.add(ownId);
        }
        if (vulnerability.aliases() != null) {
            for (String alias : vulnerability.aliases()) {
                String cveId = canonicalCveId(alias);
                if (cveId != null) {
                    ids.add(cveId);
                }
            }
        }
        return List.copyOf(ids);
    }
}
