package io.github.jdubois.bootui.engine.vulnerabilities;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes the CVSS v3.0 / v3.1 Base Score from a vector string, e.g.
 * {@code "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"} (which base-scores to {@code 9.8}).
 *
 * <p>Implements the official FIRST.org Base Metrics equations:
 *
 * <ul>
 *   <li>CVSS v3.1 &sect;7 ("CVSS v3.1 Equations"), <a
 *       href="https://www.first.org/cvss/v3-1/specification-document">specification document</a>
 *   <li>CVSS v3.0 &sect;8 ("CVSS v3.0 Equations"), <a
 *       href="https://www.first.org/cvss/v3.0/specification-document">specification document</a>
 * </ul>
 *
 * <p>The Base Metrics equations and metric-value constants (&sect;7.4 / &sect;8.4) are byte-for-byte
 * identical between v3.0 and v3.1 &mdash; only the Environmental-metrics exponent differs between the two
 * versions (13 vs 15), and BootUI never scores Temporal or Environmental metrics &mdash; so one
 * implementation serves both vector prefixes. Formula and the official integer-arithmetic "Roundup"
 * algorithm (Appendix A of both specifications) were verified end-to-end against real NVD-published Base
 * Scores, including CVE-2020-11619 (Scope Unchanged, score 8.1), CVE-2021-45046 (Scope Changed, score 9.0),
 * and CVE-2021-44228 "Log4Shell" (Scope Changed, score 10.0).
 *
 * <p>Only the mandatory Base metric group is scored (Attack Vector, Attack Complexity, Privileges
 * Required, User Interaction, Scope, and Confidentiality/Integrity/Availability impact) &mdash; this is the
 * figure OSV.dev/NVD publish and label as "the" CVSS v3 score. Any Temporal or Environmental metrics
 * present in the vector string are parsed (so they don't break Base metric extraction) but not scored.
 *
 * <p><strong>CVSS v4.0 is deliberately out of scope.</strong> Unlike v3.x, v4.0 has no closed-form Base
 * Score equation: scores are derived from a roughly 270-entry "MacroVector" lookup table (six equivalence
 * classes, each assigned by boolean conditions on the supplied metrics) with interpolation against
 * "highest/lowest severity vector" reference data from FIRST's non-public expert-elicitation study.
 * Reproducing that faithfully from the written specification alone &mdash; without executing FIRST's own
 * reference implementation &mdash; risks a silently-wrong score for a security-sensitive feature, so v4.0
 * vectors are not scored here. {@link DependencyReports#parseScore(String)} explicitly returns {@code null}
 * for {@code CVSS:4.x} vectors, and callers fall back to the {@code database_specific.severity} label for
 * those advisories.
 *
 * <p>CVSS v2 vectors (unprefixed, e.g. {@code "AV:N/AC:L/Au:N/C:P/I:P/A:P"}) are likewise not scored: the
 * format was superseded in 2015 and zero occurrences were found across a 170-advisory sample of real
 * OSV.dev responses for popular Maven packages.
 */
final class CvssV3BaseScore {

    // Section 7.4 (v3.1) / 8.4 (v3.0) metric value constants -- identical between the two spec versions.
    private static final Map<String, Double> ATTACK_VECTOR = Map.of("N", 0.85, "A", 0.62, "L", 0.55, "P", 0.2);
    private static final Map<String, Double> ATTACK_COMPLEXITY = Map.of("L", 0.77, "H", 0.44);
    private static final Map<String, Double> PRIVILEGES_REQUIRED_UNCHANGED = Map.of("N", 0.85, "L", 0.62, "H", 0.27);
    private static final Map<String, Double> PRIVILEGES_REQUIRED_CHANGED = Map.of("N", 0.85, "L", 0.68, "H", 0.5);
    private static final Map<String, Double> USER_INTERACTION = Map.of("N", 0.85, "R", 0.62);
    private static final Map<String, Double> IMPACT = Map.of("H", 0.56, "L", 0.22, "N", 0.0);

    private CvssV3BaseScore() {}

    /**
     * Computes the Base Score for a CVSS v3.0/v3.1 vector string.
     *
     * @param vector the full vector string, including its {@code CVSS:3.0/} or {@code CVSS:3.1/} prefix
     * @return the Base Score (0.0-10.0, rounded up to one decimal place per the spec's Roundup function),
     *     or {@code null} if the vector does not carry the expected prefix or is missing/has an
     *     unrecognized value for any mandatory Base metric
     */
    static Double baseScore(String vector) {
        Map<String, String> metrics = parseMetrics(vector);
        if (metrics == null) {
            return null;
        }
        String scope = metrics.get("S");
        boolean scopeChanged = "C".equals(scope);
        if (!scopeChanged && !"U".equals(scope)) {
            return null;
        }
        Double av = metricValue(ATTACK_VECTOR, metrics.get("AV"));
        Double ac = metricValue(ATTACK_COMPLEXITY, metrics.get("AC"));
        Double pr = metricValue(
                scopeChanged ? PRIVILEGES_REQUIRED_CHANGED : PRIVILEGES_REQUIRED_UNCHANGED, metrics.get("PR"));
        Double ui = metricValue(USER_INTERACTION, metrics.get("UI"));
        Double c = metricValue(IMPACT, metrics.get("C"));
        Double i = metricValue(IMPACT, metrics.get("I"));
        Double a = metricValue(IMPACT, metrics.get("A"));
        if (av == null || ac == null || pr == null || ui == null || c == null || i == null || a == null) {
            return null;
        }

        double iss = 1 - ((1 - c) * (1 - i) * (1 - a));
        double impact = scopeChanged ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15) : 6.42 * iss;
        if (impact <= 0) {
            return 0.0;
        }
        double exploitability = 8.22 * av * ac * pr * ui;
        double combined = scopeChanged ? 1.08 * (impact + exploitability) : impact + exploitability;
        return roundUp(Math.min(combined, 10.0));
    }

    /**
     * Looks up {@code rawValue} in {@code table}, tolerating a {@code null} {@code rawValue} (a mandatory
     * metric missing from the vector entirely) &mdash; the {@code Map.of(...)} lookup tables above reject a
     * {@code null} key with an {@link NullPointerException} rather than returning {@code null} the way
     * {@link java.util.HashMap} does, so callers must not pass {@code null} straight through.
     */
    private static Double metricValue(Map<String, Double> table, String rawValue) {
        return rawValue == null ? null : table.get(rawValue);
    }

    /**
     * Parses the {@code Metric:Value} segments of a vector string into a map, requiring the
     * {@code CVSS:3.0/} or {@code CVSS:3.1/} prefix. Returns {@code null} for any structurally invalid
     * vector: missing/wrong prefix, or a segment without exactly one non-edge {@code :}. Duplicate metric
     * keys keep the last occurrence, matching how the reference calculator behaves.
     */
    private static Map<String, String> parseMetrics(String vector) {
        if (vector == null) {
            return null;
        }
        String prefix;
        if (vector.startsWith("CVSS:3.0/")) {
            prefix = "CVSS:3.0/";
        } else if (vector.startsWith("CVSS:3.1/")) {
            prefix = "CVSS:3.1/";
        } else {
            return null;
        }
        Map<String, String> metrics = new HashMap<>();
        for (String segment : vector.substring(prefix.length()).split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            int separator = segment.indexOf(':');
            if (separator <= 0 || separator == segment.length() - 1) {
                return null;
            }
            metrics.put(segment.substring(0, separator), segment.substring(separator + 1));
        }
        return metrics;
    }

    /**
     * The official CVSS "Roundup" function (Appendix A of both the v3.0 and v3.1 specifications): rounds a
     * score up to the nearest 0.1 using integer arithmetic, avoiding the floating-point misrounds a naive
     * {@code Math.ceil(input * 10) / 10.0} can produce (the spec's own example: 0.1 + 0.2 represented as
     * 0.30000000000000004 in IEEE-754 misrounds up to 0.4 instead of staying at 0.3).
     */
    private static double roundUp(double input) {
        long intInput = Math.round(input * 100000);
        if (intInput % 10000 == 0) {
            return intInput / 100000.0;
        }
        return (Math.floor(intInput / 10000.0) + 1) / 10.0;
    }
}
