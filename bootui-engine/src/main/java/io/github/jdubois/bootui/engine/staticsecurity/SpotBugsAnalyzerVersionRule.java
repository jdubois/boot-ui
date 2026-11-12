package io.github.jdubois.bootui.engine.staticsecurity;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import java.util.List;

/**
 * Finding metadata for {@code SEC-STATIC-002}: a declared {@code spotbugs-maven-plugin} version too old to read the
 * project's Java release. SpotBugs that predates ASM support for a Java release fails on that bytecode with
 * {@code Unsupported class file major version} and silently produces no findings — a configured analyzer that is not
 * actually analyzing. Kept outside runtime rule evaluation so it cannot make runtime evidence usable.
 */
public final class SpotBugsAnalyzerVersionRule {

    public static final String RULE_ID = "SEC-STATIC-002";

    private SpotBugsAnalyzerVersionRule() {}

    public static SecurityRuleResultDto finding(
            AdvisorViolationCollector collector, StaticSecurityAnalysisDetector.SpotBugsCompatibility compatibility) {
        String detail = "The declared spotbugs-maven-plugin bundles SpotBugs core "
                + compatibility.declaredCore() + ", which predates ASM support for Java "
                + compatibility.javaRelease() + " bytecode. SpotBugs fails on these class files with "
                + "\"Unsupported class file major version\" and produces no findings, so the configured analyzer is "
                + "not actually analyzing the code.";
        collector.record(RULE_ID, 1, List.of(detail), value -> value);
        return new SecurityRuleResultDto(
                RULE_ID,
                "SpotBugs version cannot analyze this Java release",
                "Static analysis",
                "MEDIUM",
                detail + " Version-to-Java support is derived from the bundled ASM; BootUI reads only the declared "
                        + "version and does not run the analyzer.",
                "VIOLATION",
                1,
                List.of(detail),
                "Upgrade com.github.spotbugs:spotbugs-maven-plugin to " + compatibility.minimumCore()
                        + ".0 or later (the first release whose SpotBugs core reads Java "
                        + compatibility.javaRelease() + " bytecode) and re-run ./mvnw spotbugs:spotbugs.",
                "https://spotbugs.readthedocs.io/en/stable/");
    }
}
