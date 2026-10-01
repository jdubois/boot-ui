/*
 * The MacroVector lookup table, maximal vectors, maximal severity depths and scoring algorithm in this file
 * are ported from FIRST's CVSS v4.0 reference calculator (https://github.com/FIRSTdotorg/cvss-v4-calculator),
 * distributed under the following license:
 *
 * Copyright (c) 2023 FIRST.ORG, Inc., Red Hat, and contributors
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.github.jdubois.bootui.engine.vulnerabilities;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the CVSS v4.0 score of a vector string, e.g.
 * {@code "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"} (which scores {@code 9.3}).
 *
 * <p>CVSS v4.0 has no closed-form equation: a vector is classified into a MacroVector (equivalence sets
 * EQ1&ndash;EQ6), whose expert-assigned score is then lowered by the vector's mean proportional severity
 * distance from that MacroVector's highest-severity vectors. This is a faithful port of FIRST's
 * <a href="https://www.first.org/cvss/v4.0/specification-document">specification</a> reference calculator,
 * checked against it for every one of the 104,976 Base metric combinations and for Threat and
 * Environmental variants.
 *
 * <p>The vector is scored as supplied, exactly as FIRST's calculator and the GitHub Advisory Database do: a
 * Base-only vector yields CVSS-B, and a published Threat metric (GitHub vectors often carry {@code E:U}) or
 * Environmental metric yields CVSS-BT/BE/BTE. Supplemental metrics are validated but never affect the score.
 */
final class CvssV4Score {

    private static final String PREFIX = "CVSS:4.0/";

    private static final List<String> BASE_METRICS =
            List.of("AV", "AC", "AT", "PR", "UI", "VC", "VI", "VA", "SC", "SI", "SA");

    private static final Map<String, Set<String>> VALID_METRICS = Map.ofEntries(
            Map.entry("AV", Set.of("N", "A", "L", "P")),
            Map.entry("AC", Set.of("L", "H")),
            Map.entry("AT", Set.of("N", "P")),
            Map.entry("PR", Set.of("N", "L", "H")),
            Map.entry("UI", Set.of("N", "P", "A")),
            Map.entry("VC", Set.of("H", "L", "N")),
            Map.entry("VI", Set.of("H", "L", "N")),
            Map.entry("VA", Set.of("H", "L", "N")),
            Map.entry("SC", Set.of("H", "L", "N")),
            Map.entry("SI", Set.of("H", "L", "N")),
            Map.entry("SA", Set.of("H", "L", "N")),
            Map.entry("E", Set.of("X", "A", "P", "U")),
            Map.entry("CR", Set.of("X", "H", "M", "L")),
            Map.entry("IR", Set.of("X", "H", "M", "L")),
            Map.entry("AR", Set.of("X", "H", "M", "L")),
            Map.entry("MAV", Set.of("X", "N", "A", "L", "P")),
            Map.entry("MAC", Set.of("X", "L", "H")),
            Map.entry("MAT", Set.of("X", "N", "P")),
            Map.entry("MPR", Set.of("X", "N", "L", "H")),
            Map.entry("MUI", Set.of("X", "N", "P", "A")),
            Map.entry("MVC", Set.of("X", "H", "L", "N")),
            Map.entry("MVI", Set.of("X", "H", "L", "N")),
            Map.entry("MVA", Set.of("X", "H", "L", "N")),
            Map.entry("MSC", Set.of("X", "H", "L", "N")),
            Map.entry("MSI", Set.of("X", "S", "H", "L", "N")),
            Map.entry("MSA", Set.of("X", "S", "H", "L", "N")),
            Map.entry("S", Set.of("X", "N", "P")),
            Map.entry("AU", Set.of("X", "N", "Y")),
            Map.entry("R", Set.of("X", "A", "U", "I")),
            Map.entry("V", Set.of("X", "D", "C")),
            Map.entry("RE", Set.of("X", "L", "M", "H")),
            Map.entry("U", Set.of("X", "Clear", "Green", "Amber", "Red")));

    private static final Map<String, Double> AV_LEVELS = Map.of("N", 0.0, "A", 0.1, "L", 0.2, "P", 0.3);
    private static final Map<String, Double> PR_LEVELS = Map.of("N", 0.0, "L", 0.1, "H", 0.2);
    private static final Map<String, Double> UI_LEVELS = Map.of("N", 0.0, "P", 0.1, "A", 0.2);
    private static final Map<String, Double> AC_LEVELS = Map.of("L", 0.0, "H", 0.1);
    private static final Map<String, Double> AT_LEVELS = Map.of("N", 0.0, "P", 0.1);
    private static final Map<String, Double> VULNERABLE_LEVELS = Map.of("H", 0.0, "L", 0.1, "N", 0.2);
    private static final Map<String, Double> SC_LEVELS = Map.of("H", 0.1, "L", 0.2, "N", 0.3);
    private static final Map<String, Double> SUBSEQUENT_LEVELS = Map.of("S", 0.0, "H", 0.1, "L", 0.2, "N", 0.3);
    private static final Map<String, Double> REQUIREMENT_LEVELS = Map.of("H", 0.0, "M", 0.1, "L", 0.2);

    private static final Map<String, List<String>> EQ1_MAXES = Map.of(
            "0", List.of("AV:N/PR:N/UI:N"),
            "1", List.of("AV:A/PR:N/UI:N", "AV:N/PR:L/UI:N", "AV:N/PR:N/UI:P"),
            "2", List.of("AV:P/PR:N/UI:N", "AV:A/PR:L/UI:P"));

    private static final Map<String, List<String>> EQ2_MAXES =
            Map.of("0", List.of("AC:L/AT:N"), "1", List.of("AC:H/AT:N", "AC:L/AT:P"));

    /** Keyed by EQ3 then EQ6, as FIRST's {@code maxComposed.eq3}. */
    private static final Map<String, List<String>> EQ3_EQ6_MAXES = Map.of(
            "00",
            List.of("VC:H/VI:H/VA:H/CR:H/IR:H/AR:H"),
            "01",
            List.of("VC:H/VI:H/VA:L/CR:M/IR:M/AR:H", "VC:H/VI:H/VA:H/CR:M/IR:M/AR:M"),
            "10",
            List.of("VC:L/VI:H/VA:H/CR:H/IR:H/AR:H", "VC:H/VI:L/VA:H/CR:H/IR:H/AR:H"),
            "11",
            List.of(
                    "VC:L/VI:H/VA:L/CR:H/IR:M/AR:H",
                    "VC:L/VI:H/VA:H/CR:H/IR:M/AR:M",
                    "VC:H/VI:L/VA:H/CR:M/IR:H/AR:M",
                    "VC:H/VI:L/VA:L/CR:M/IR:H/AR:H",
                    "VC:L/VI:L/VA:H/CR:H/IR:H/AR:M"),
            "21",
            List.of("VC:L/VI:L/VA:L/CR:H/IR:H/AR:H"));

    private static final Map<String, List<String>> EQ4_MAXES =
            Map.of("0", List.of("SC:H/SI:S/SA:S"), "1", List.of("SC:H/SI:H/SA:H"), "2", List.of("SC:L/SI:L/SA:L"));

    private static final Map<String, List<String>> EQ5_MAXES =
            Map.of("0", List.of("E:A"), "1", List.of("E:P"), "2", List.of("E:U"));

    private static final Map<String, Integer> EQ1_DEPTH = Map.of("0", 1, "1", 4, "2", 5);
    private static final Map<String, Integer> EQ2_DEPTH = Map.of("0", 1, "1", 2);
    private static final Map<String, Integer> EQ3_EQ6_DEPTH = Map.of("00", 7, "01", 6, "10", 8, "11", 8, "21", 10);
    private static final Map<String, Integer> EQ4_DEPTH = Map.of("0", 6, "1", 5, "2", 4);

    /** MacroVector scores from FIRST's {@code cvss_lookup.js}. */
    private static final Map<String, Double> LOOKUP = lookup("""
            000000=10 000001=9.9 000010=9.8 000011=9.5 000020=9.5 000021=9.2 000100=10 000101=9.6
            000110=9.3 000111=8.7 000120=9.1 000121=8.1 000200=9.3 000201=9 000210=8.9 000211=8
            000220=8.1 000221=6.8 001000=9.8 001001=9.5 001010=9.5 001011=9.2 001020=9 001021=8.4
            001100=9.3 001101=9.2 001110=8.9 001111=8.1 001120=8.1 001121=6.5 001200=8.8 001201=8
            001210=7.8 001211=7 001220=6.9 001221=4.8 002001=9.2 002011=8.2 002021=7.2 002101=7.9
            002111=6.9 002121=5 002201=6.9 002211=5.5 002221=2.7 010000=9.9 010001=9.7 010010=9.5
            010011=9.2 010020=9.2 010021=8.5 010100=9.5 010101=9.1 010110=9 010111=8.3 010120=8.4
            010121=7.1 010200=9.2 010201=8.1 010210=8.2 010211=7.1 010220=7.2 010221=5.3 011000=9.5
            011001=9.3 011010=9.2 011011=8.5 011020=8.5 011021=7.3 011100=9.2 011101=8.2 011110=8
            011111=7.2 011120=7 011121=5.9 011200=8.4 011201=7 011210=7.1 011211=5.2 011220=5
            011221=3 012001=8.6 012011=7.5 012021=5.2 012101=7.1 012111=5.2 012121=2.9 012201=6.3
            012211=2.9 012221=1.7 100000=9.8 100001=9.5 100010=9.4 100011=8.7 100020=9.1 100021=8.1
            100100=9.4 100101=8.9 100110=8.6 100111=7.4 100120=7.7 100121=6.4 100200=8.7 100201=7.5
            100210=7.4 100211=6.3 100220=6.3 100221=4.9 101000=9.4 101001=8.9 101010=8.8 101011=7.7
            101020=7.6 101021=6.7 101100=8.6 101101=7.6 101110=7.4 101111=5.8 101120=5.9 101121=5
            101200=7.2 101201=5.7 101210=5.7 101211=5.2 101220=5.2 101221=2.5 102001=8.3 102011=7
            102021=5.4 102101=6.5 102111=5.8 102121=2.6 102201=5.3 102211=2.1 102221=1.3 110000=9.5
            110001=9 110010=8.8 110011=7.6 110020=7.6 110021=7 110100=9 110101=7.7 110110=7.5
            110111=6.2 110120=6.1 110121=5.3 110200=7.7 110201=6.6 110210=6.8 110211=5.9 110220=5.2
            110221=3 111000=8.9 111001=7.8 111010=7.6 111011=6.7 111020=6.2 111021=5.8 111100=7.4
            111101=5.9 111110=5.7 111111=5.7 111120=4.7 111121=2.3 111200=6.1 111201=5.2 111210=5.7
            111211=2.9 111220=2.4 111221=1.6 112001=7.1 112011=5.9 112021=3 112101=5.8 112111=2.6
            112121=1.5 112201=2.3 112211=1.3 112221=0.6 200000=9.3 200001=8.7 200010=8.6 200011=7.2
            200020=7.5 200021=5.8 200100=8.6 200101=7.4 200110=7.4 200111=6.1 200120=5.6 200121=3.4
            200200=7 200201=5.4 200210=5.2 200211=4 200220=4 200221=2.2 201000=8.5 201001=7.5
            201010=7.4 201011=5.5 201020=6.2 201021=5.1 201100=7.2 201101=5.7 201110=5.5 201111=4.1
            201120=4.6 201121=1.9 201200=5.3 201201=3.6 201210=3.4 201211=1.9 201220=1.9 201221=0.8
            202001=6.4 202011=5.1 202021=2 202101=4.7 202111=2.1 202121=1.1 202201=2.4 202211=0.9
            202221=0.4 210000=8.8 210001=7.5 210010=7.3 210011=5.3 210020=6 210021=5 210100=7.3
            210101=5.5 210110=5.9 210111=4 210120=4.1 210121=2 210200=5.4 210201=4.3 210210=4.5
            210211=2.2 210220=2 210221=1.1 211000=7.5 211001=5.5 211010=5.8 211011=4.5 211020=4
            211021=2.1 211100=6.1 211101=5.1 211110=4.8 211111=1.8 211120=2 211121=0.9 211200=4.6
            211201=1.8 211210=1.7 211211=0.7 211220=0.8 211221=0.2 212001=5.3 212011=2.4 212021=1.4
            212101=2.4 212111=1.2 212121=0.5 212201=1 212211=0.3 212221=0.1
            """);

    private CvssV4Score() {}

    /**
     * @param vector the full vector string, including its {@code CVSS:4.0/} prefix
     * @return the score of the vector as supplied (one decimal place, 0.0-10.0), or {@code null} for a
     *     missing/wrong prefix, a missing Base metric, or an empty, malformed, unknown, duplicate or
     *     invalid-valued segment
     */
    static Double score(String vector) {
        Map<String, String> metrics = parseMetrics(vector);
        if (metrics == null || !metrics.keySet().containsAll(BASE_METRICS)) {
            return null;
        }
        return score(metrics);
    }

    private static Double score(Map<String, String> selected) {
        Map<String, String> m = effective(selected);
        if (List.of("VC", "VI", "VA", "SC", "SI", "SA").stream().allMatch(metric -> "N".equals(m.get(metric)))) {
            return 0.0;
        }
        String macro = macroVector(m);
        double value = LOOKUP.get(macro);
        int eq1 = macro.charAt(0) - '0';
        int eq2 = macro.charAt(1) - '0';
        int eq3 = macro.charAt(2) - '0';
        int eq4 = macro.charAt(3) - '0';
        int eq5 = macro.charAt(4) - '0';
        int eq6 = macro.charAt(5) - '0';

        double scoreEq1NextLower = lookup(eq1 + 1, eq2, eq3, eq4, eq5, eq6);
        double scoreEq2NextLower = lookup(eq1, eq2 + 1, eq3, eq4, eq5, eq6);
        double scoreEq3Eq6NextLower;
        if (eq3 == 1 && eq6 == 1) {
            scoreEq3Eq6NextLower = lookup(eq1, eq2, eq3 + 1, eq4, eq5, eq6);
        } else if (eq3 == 0 && eq6 == 1) {
            scoreEq3Eq6NextLower = lookup(eq1, eq2, eq3 + 1, eq4, eq5, eq6);
        } else if (eq3 == 1 && eq6 == 0) {
            scoreEq3Eq6NextLower = lookup(eq1, eq2, eq3, eq4, eq5, eq6 + 1);
        } else if (eq3 == 0 && eq6 == 0) {
            double left = lookup(eq1, eq2, eq3, eq4, eq5, eq6 + 1);
            double right = lookup(eq1, eq2, eq3 + 1, eq4, eq5, eq6);
            scoreEq3Eq6NextLower = left > right ? left : right;
        } else {
            scoreEq3Eq6NextLower = lookup(eq1, eq2, eq3 + 1, eq4, eq5, eq6 + 1);
        }
        double scoreEq4NextLower = lookup(eq1, eq2, eq3, eq4 + 1, eq5, eq6);
        double scoreEq5NextLower = lookup(eq1, eq2, eq3, eq4, eq5 + 1, eq6);

        // Find the first highest-severity vector of this MacroVector that the scored vector does not exceed.
        double[] distances = null;
        for (String eq1Max : EQ1_MAXES.get(String.valueOf(eq1))) {
            for (String eq2Max : EQ2_MAXES.get(String.valueOf(eq2))) {
                for (String eq3Eq6Max : EQ3_EQ6_MAXES.get("" + eq3 + eq6)) {
                    for (String eq4Max : EQ4_MAXES.get(String.valueOf(eq4))) {
                        for (String eq5Max : EQ5_MAXES.get(String.valueOf(eq5))) {
                            if (distances != null && nonNegative(distances)) {
                                continue;
                            }
                            distances =
                                    distances(m, metrics(String.join("/", eq1Max, eq2Max, eq3Eq6Max, eq4Max, eq5Max)));
                        }
                    }
                }
            }
        }
        double distanceEq1 = distances[0] + distances[1] + distances[2];
        double distanceEq2 = distances[3] + distances[4];
        double distanceEq3Eq6 =
                distances[5] + distances[6] + distances[7] + distances[11] + distances[12] + distances[13];
        double distanceEq4 = distances[8] + distances[9] + distances[10];

        double step = 0.1;
        double availableEq1 = value - scoreEq1NextLower;
        double availableEq2 = value - scoreEq2NextLower;
        double availableEq3Eq6 = value - scoreEq3Eq6NextLower;
        double availableEq4 = value - scoreEq4NextLower;
        double availableEq5 = value - scoreEq5NextLower;

        double maxSeverityEq1 = EQ1_DEPTH.get(String.valueOf(eq1)) * step;
        double maxSeverityEq2 = EQ2_DEPTH.get(String.valueOf(eq2)) * step;
        double maxSeverityEq3Eq6 = EQ3_EQ6_DEPTH.get("" + eq3 + eq6) * step;
        double maxSeverityEq4 = EQ4_DEPTH.get(String.valueOf(eq4)) * step;

        int existingLower = 0;
        double normalizedEq1 = 0;
        double normalizedEq2 = 0;
        double normalizedEq3Eq6 = 0;
        double normalizedEq4 = 0;
        double normalizedEq5 = 0;
        if (!Double.isNaN(availableEq1)) {
            existingLower++;
            normalizedEq1 = availableEq1 * (distanceEq1 / maxSeverityEq1);
        }
        if (!Double.isNaN(availableEq2)) {
            existingLower++;
            normalizedEq2 = availableEq2 * (distanceEq2 / maxSeverityEq2);
        }
        if (!Double.isNaN(availableEq3Eq6)) {
            existingLower++;
            normalizedEq3Eq6 = availableEq3Eq6 * (distanceEq3Eq6 / maxSeverityEq3Eq6);
        }
        if (!Double.isNaN(availableEq4)) {
            existingLower++;
            normalizedEq4 = availableEq4 * (distanceEq4 / maxSeverityEq4);
        }
        if (!Double.isNaN(availableEq5)) {
            // EQ5's proportional distance is always zero.
            existingLower++;
            normalizedEq5 = availableEq5 * 0;
        }
        double meanDistance = existingLower == 0
                ? 0
                : (normalizedEq1 + normalizedEq2 + normalizedEq3Eq6 + normalizedEq4 + normalizedEq5) / existingLower;
        value -= meanDistance;
        if (value < 0) {
            value = 0.0;
        }
        if (value > 10) {
            value = 10.0;
        }
        // JavaScript's Math.round and Java's Math.round(double) both round half up.
        return Math.round(value * 10) / 10.0;
    }

    /**
     * Resolves every scoring metric as FIRST's {@code m()} does: an undefined Exploit Maturity is the worst
     * case {@code A}, undefined security requirements are {@code H}, and a defined modified Base metric
     * overrides its Base value.
     */
    private static Map<String, String> effective(Map<String, String> selected) {
        Map<String, String> m = new HashMap<>();
        for (String metric : List.of(
                "AV", "AC", "AT", "PR", "UI", "VC", "VI", "VA", "SC", "SI", "SA", "MSI", "MSA", "E", "CR", "IR",
                "AR")) {
            String value = selected.getOrDefault(metric, "X");
            if ("E".equals(metric) && "X".equals(value)) {
                value = "A";
            } else if (List.of("CR", "IR", "AR").contains(metric) && "X".equals(value)) {
                value = "H";
            } else {
                String modified = selected.getOrDefault("M" + metric, "X");
                if (!"X".equals(modified)) {
                    value = modified;
                }
            }
            m.put(metric, value);
        }
        return m;
    }

    private static boolean nonNegative(double[] distances) {
        for (double distance : distances) {
            if (distance < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Severity distances, in FIRST's order: AV, PR, UI, AC, AT, VC, VI, VA, SC, SI, SA, CR, IR, AR.
     */
    private static double[] distances(Map<String, String> m, Map<String, String> max) {
        return new double[] {
            AV_LEVELS.get(m.get("AV")) - AV_LEVELS.get(max.get("AV")),
            PR_LEVELS.get(m.get("PR")) - PR_LEVELS.get(max.get("PR")),
            UI_LEVELS.get(m.get("UI")) - UI_LEVELS.get(max.get("UI")),
            AC_LEVELS.get(m.get("AC")) - AC_LEVELS.get(max.get("AC")),
            AT_LEVELS.get(m.get("AT")) - AT_LEVELS.get(max.get("AT")),
            VULNERABLE_LEVELS.get(m.get("VC")) - VULNERABLE_LEVELS.get(max.get("VC")),
            VULNERABLE_LEVELS.get(m.get("VI")) - VULNERABLE_LEVELS.get(max.get("VI")),
            VULNERABLE_LEVELS.get(m.get("VA")) - VULNERABLE_LEVELS.get(max.get("VA")),
            SC_LEVELS.get(m.get("SC")) - SC_LEVELS.get(max.get("SC")),
            SUBSEQUENT_LEVELS.get(m.get("SI")) - SUBSEQUENT_LEVELS.get(max.get("SI")),
            SUBSEQUENT_LEVELS.get(m.get("SA")) - SUBSEQUENT_LEVELS.get(max.get("SA")),
            REQUIREMENT_LEVELS.get(m.get("CR")) - REQUIREMENT_LEVELS.get(max.get("CR")),
            REQUIREMENT_LEVELS.get(m.get("IR")) - REQUIREMENT_LEVELS.get(max.get("IR")),
            REQUIREMENT_LEVELS.get(m.get("AR")) - REQUIREMENT_LEVELS.get(max.get("AR"))
        };
    }

    private static String macroVector(Map<String, String> m) {
        boolean avN = is(m, "AV", "N");
        boolean prN = is(m, "PR", "N");
        boolean uiN = is(m, "UI", "N");
        String eq1;
        if (avN && prN && uiN) {
            eq1 = "0";
        } else if ((avN || prN || uiN) && !is(m, "AV", "P")) {
            eq1 = "1";
        } else {
            eq1 = "2";
        }
        String eq2 = is(m, "AC", "L") && is(m, "AT", "N") ? "0" : "1";
        boolean vcH = is(m, "VC", "H");
        boolean viH = is(m, "VI", "H");
        boolean vaH = is(m, "VA", "H");
        String eq3 = vcH && viH ? "0" : vcH || viH || vaH ? "1" : "2";
        String eq4;
        if (is(m, "MSI", "S") || is(m, "MSA", "S")) {
            eq4 = "0";
        } else if (is(m, "SC", "H") || is(m, "SI", "H") || is(m, "SA", "H")) {
            eq4 = "1";
        } else {
            eq4 = "2";
        }
        String eq5 = is(m, "E", "A") ? "0" : is(m, "E", "P") ? "1" : "2";
        String eq6 = is(m, "CR", "H") && vcH || is(m, "IR", "H") && viH || is(m, "AR", "H") && vaH ? "0" : "1";
        return eq1 + eq2 + eq3 + eq4 + eq5 + eq6;
    }

    private static boolean is(Map<String, String> m, String metric, String value) {
        return value.equals(m.get(metric));
    }

    /** A missing MacroVector scores {@code NaN}, as FIRST's {@code undefined} lookup does. */
    private static double lookup(int eq1, int eq2, int eq3, int eq4, int eq5, int eq6) {
        Double score = LOOKUP.get("" + eq1 + eq2 + eq3 + eq4 + eq5 + eq6);
        return score == null ? Double.NaN : score;
    }

    private static Map<String, String> metrics(String vector) {
        Map<String, String> metrics = new HashMap<>();
        for (String segment : vector.split("/")) {
            int separator = segment.indexOf(':');
            metrics.put(segment.substring(0, separator), segment.substring(separator + 1));
        }
        return metrics;
    }

    /**
     * Parses the {@code Metric:Value} segments after a {@code CVSS:4.0/} prefix. Returns {@code null} for an
     * empty segment, a segment without exactly one non-edge {@code :}, an unknown metric or value, or a
     * duplicate metric.
     */
    private static Map<String, String> parseMetrics(String vector) {
        if (vector == null || !vector.startsWith(PREFIX)) {
            return null;
        }
        Map<String, String> metrics = new HashMap<>();
        for (String segment : vector.substring(PREFIX.length()).split("/", -1)) {
            int separator = segment.indexOf(':');
            if (separator <= 0 || separator == segment.length() - 1 || segment.indexOf(':', separator + 1) >= 0) {
                return null;
            }
            String metric = segment.substring(0, separator);
            String value = segment.substring(separator + 1);
            Set<String> allowed = VALID_METRICS.get(metric);
            if (allowed == null || !allowed.contains(value) || metrics.putIfAbsent(metric, value) != null) {
                return null;
            }
        }
        return metrics;
    }

    private static Map<String, Double> lookup(String table) {
        Map<String, Double> scores = new HashMap<>();
        for (String entry : table.trim().split("\\s+")) {
            int separator = entry.indexOf('=');
            scores.put(entry.substring(0, separator), Double.valueOf(entry.substring(separator + 1)));
        }
        return Map.copyOf(scores);
    }
}
