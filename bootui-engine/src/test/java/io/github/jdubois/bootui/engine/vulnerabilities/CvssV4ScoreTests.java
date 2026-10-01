package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link CvssV4Score} against FIRST's CVSS v4.0 reference calculator and against vectors published
 * by the GitHub Advisory Database for real Maven advisories.
 */
class CvssV4ScoreTests {

    private static final String BASE = "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N";

    @Test
    void matchesFirstReferenceCalculatorForEveryMacroVectorAndSampledVectors() throws IOException {
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        try (InputStream input = CvssV4ScoreTests.class.getResourceAsStream("cvss-v4-first-reference.csv");
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int comma = line.lastIndexOf(',');
                double expected = Double.parseDouble(line.substring(comma + 1));
                Double actual = CvssV4Score.score("CVSS:4.0/" + line.substring(0, comma));
                checked++;
                if (actual == null || actual != expected) {
                    mismatches.add(line + " -> " + actual);
                }
            }
        }
        assertThat(checked).isGreaterThan(350);
        assertThat(mismatches).isEmpty();
    }

    @Test
    void scoresFirstSpecificationExample() {
        assertThat(CvssV4Score.score(BASE)).isEqualTo(9.3);
        assertThat(CvssV4Score.score("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:H/SI:H/SA:H"))
                .isEqualTo(10.0);
    }

    @Test
    void scoresRealGitHubAdvisoryVectorsLikeTheirPublishedSeverity() {
        // GHSA-3pxv-7cmr-fjr4 (log4j-core, v4 only, GitHub MODERATE).
        assertThat(CvssV4Score.score("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N"))
                .isEqualTo(6.9);
        // GHSA-fpj8-gq4v-p354 (tomcat-embed-core, GitHub MODERATE, CVSS v3 9.1 CRITICAL).
        assertThat(CvssV4Score.score("CVSS:4.0/AV:N/AC:L/AT:P/PR:N/UI:N/VC:L/VI:L/VA:N/SC:N/SI:N/SA:N"))
                .isEqualTo(6.3);
        // GHSA-5j33-cvvr-w245 (tomcat-catalina, GitHub HIGH): the published E:U Threat metric lowers 9.2 to 7.2.
        assertThat(CvssV4Score.score("CVSS:4.0/AV:N/AC:L/AT:P/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:U"))
                .isEqualTo(7.2);
        assertThat(CvssV4Score.score("CVSS:4.0/AV:N/AC:L/AT:P/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
                .isEqualTo(9.2);
        // GHSA-hgrr-935x-pq79 (tomcat, GitHub LOW, CVSS v3 5.9 MEDIUM).
        assertThat(CvssV4Score.score("CVSS:4.0/AV:N/AC:L/AT:P/PR:L/UI:N/VC:N/VI:N/VA:H/SC:N/SI:N/SA:N/E:U"))
                .isEqualTo(2.3);
    }

    @Test
    void noImpactOnAnySystemScoresZero() {
        assertThat(CvssV4Score.score("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:N/SA:N"))
                .isEqualTo(0.0);
    }

    @Test
    void acceptsAnyMetricOrderAndIgnoresSupplementalMetrics() {
        assertThat(CvssV4Score.score("CVSS:4.0/SA:N/SI:N/SC:N/VA:H/VI:H/VC:H/UI:N/PR:N/AT:N/AC:L/AV:N"))
                .isEqualTo(9.3);
        assertThat(CvssV4Score.score(BASE + "/S:P/AU:Y/R:I/V:C/RE:H/U:Red")).isEqualTo(9.3);
        assertThat(CvssV4Score.score(BASE + "/E:X/CR:X/MAV:X")).isEqualTo(9.3);
    }

    @Test
    void appliesThreatAndEnvironmentalMetricsWhenSupplied() {
        assertThat(CvssV4Score.score(BASE + "/E:U")).isLessThan(9.3);
        assertThat(CvssV4Score.score(BASE + "/MAV:P")).isLessThan(9.3);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N",
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "CVSS:4.1/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N",
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N",
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/",
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/AV:N",
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/XX:Y",
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:S",
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:Z",
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/U:red",
                "CVSS:4.0/AV:n/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N",
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N:N",
                "CVSS:4.0/AV:N//AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N",
                "9.3",
                ""
            })
    void rejectsMalformedVectors(String vector) {
        assertThat(CvssV4Score.score(vector)).isNull();
    }

    @Test
    void rejectsNull() {
        assertThat(CvssV4Score.score(null)).isNull();
    }
}
