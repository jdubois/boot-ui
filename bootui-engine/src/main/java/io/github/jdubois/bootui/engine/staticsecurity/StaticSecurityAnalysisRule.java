package io.github.jdubois.bootui.engine.staticsecurity;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import java.util.ArrayList;
import java.util.List;

/** Shared finding metadata kept outside runtime rule evaluation so it cannot make runtime evidence usable. */
public final class StaticSecurityAnalysisRule {

    private static final String DETAIL =
            "No recognized static security analyzer declaration was found in the inspected local project files.";

    private StaticSecurityAnalysisRule() {}

    public static SecurityRuleResultDto finding(AdvisorViolationCollector collector) {
        collector.record(StaticSecurityAnalysisDetector.RULE_ID, 1, List.of(DETAIL), value -> value);
        return new SecurityRuleResultDto(
                StaticSecurityAnalysisDetector.RULE_ID,
                "No local static security analysis declared",
                "Static analysis",
                "INFO",
                DETAIL + " External Maven parents, Gradle applied scripts or convention plugins, and "
                        + "organization-managed CI configuration are not resolved. BootUI does not run analyzers or "
                        + "verify execution.",
                "VIOLATION",
                1,
                List.of(DETAIL),
                "For Maven, add com.github.spotbugs:spotbugs-maven-plugin with the "
                        + "com.h3xstream.findsecbugs:findsecbugs-plugin detector and run ./mvnw spotbugs:spotbugs. "
                        + "For Gradle, apply com.github.spotbugs, add "
                        + "com.h3xstream.findsecbugs:findsecbugs-plugin to spotbugsPlugins, and run "
                        + "./gradlew spotbugsMain. Alternatively declare Semgrep, SonarQube, or CodeQL in supported "
                        + "local configuration. BootUI does not install or run analyzers.",
                "https://find-sec-bugs.github.io/");
    }

    public static AdvisorEvidenceDto addAssessmentLimitation(AdvisorEvidenceDto evidence) {
        List<String> limitations = new ArrayList<>(evidence.limitations());
        limitations.add("Static analyzer configuration could not be assessed from bounded local files.");
        return new AdvisorEvidenceDto(evidence.usable(), false, limitations);
    }
}
