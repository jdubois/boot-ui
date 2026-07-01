package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link CvssV3BaseScore} against real, NVD-published Base Scores and the FIRST.org CVSS v3.0/v3.1
 * specifications' worked/edge cases -- not just internally-consistent arithmetic.
 */
class CvssV3BaseScoreTests {

    @Test
    void matchesNvdPublishedScoreForCve202011619ScopeUnchanged() {
        // jackson-databind gadget-chain RCE; NVD Base Score 8.1 (CVSS 3.1, Scope Unchanged).
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isCloseTo(8.1d, within(0.001));
    }

    @Test
    void matchesNvdPublishedScoreForCve202145046ScopeChanged() {
        // log4j-core incomplete-fix RCE follow-up; NVD Base Score 9.0 (CVSS 3.1, Scope Changed).
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:C/C:H/I:H/A:H"))
                .isCloseTo(9.0d, within(0.001));
    }

    @Test
    void matchesNvdPublishedScoreForLog4Shell() {
        // CVE-2021-44228 "Log4Shell"; NVD Base Score 10.0 (CVSS 3.1, Scope Changed) -- the maximum score.
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"))
                .isCloseTo(10.0d, within(0.001));
    }

    @Test
    void computesTheCommonCriticalNetworkVector() {
        // The frequently-seen "textbook" unauthenticated-RCE vector, Scope Unchanged.
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isCloseTo(9.8d, within(0.001));
    }

    @Test
    void computesARealModerateSeverityVectorObservedOnOsvDev() {
        // Matches a real OSV.dev MODERATE-labelled advisory's CVSS_V3 vector (log4j-core DoS family).
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:U/C:L/I:L/A:N"))
                .isCloseTo(5.4d, within(0.001));
    }

    @Test
    void returnsZeroWhenThereIsNoConfidentialityIntegrityOrAvailabilityImpact() {
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"))
                .isEqualTo(0.0d);
    }

    @Test
    void supportsTheCvss30PrefixWithTheIdenticalFormula() {
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isCloseTo(9.8d, within(0.001));
    }

    @Test
    void ignoresTrailingTemporalMetricsWhenComputingTheBaseScore() {
        // Temporal metrics (E/RL/RC) may be appended after the Base metrics in a real vector string; the
        // Base Score must ignore them rather than fail to parse.
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H/E:P/RL:O/RC:C"))
                .isCloseTo(9.8d, within(0.001));
    }

    @Test
    void toleratesMetricsInNonPreferredOrder() {
        // Per spec section 6.3, metrics may appear in any order in the vector string.
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/S:U/AV:N/AC:L/PR:H/UI:N/C:L/I:L/A:N"))
                .isCloseTo(3.8d, within(0.001));
    }

    @Test
    void returnsNullForACvss40Vector() {
        // v4.0 has no closed-form Base Score equation; deliberately not computed (see class Javadoc).
        assertThat(CvssV3BaseScore.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N"))
                .isNull();
    }

    @Test
    void returnsNullForNull() {
        assertThat(CvssV3BaseScore.baseScore(null)).isNull();
    }

    @Test
    void returnsNullWhenTheVectorHasNoCvssPrefix() {
        assertThat(CvssV3BaseScore.baseScore("AV:N/AC:L/Au:N/C:P/I:P/A:P")).isNull();
    }

    @Test
    void returnsNullWhenAMandatoryBaseMetricIsMissing() {
        // Missing "A" (Availability).
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H"))
                .isNull();
    }

    @Test
    void returnsNullWhenAMetricValueIsNotRecognized() {
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:X/C:H/I:H/A:H"))
                .isNull();
    }

    @Test
    void returnsNullForAMalformedSegmentWithNoColon() {
        assertThat(CvssV3BaseScore.baseScore("CVSS:3.1/AV:N/GARBAGE/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isNull();
    }
}
