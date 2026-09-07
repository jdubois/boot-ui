package io.github.jdubois.bootui.spi;

import java.util.List;
import java.util.Set;

/**
 * Bounded, framework-neutral application evidence. Settings contain only allowlisted classifications,
 * never raw configuration values, URLs, or exception messages.
 */
public record QuarkusAppSnapshot(
        QuarkusAppMetadata metadata,
        List<String> activeProfiles,
        int runtimeJdkMajorVersion,
        List<Setting> settings,
        Set<String> evaluatedConfigurationRules,
        List<QuarkusAppEvidenceProblem> problems) {

    public QuarkusAppSnapshot {
        activeProfiles = List.copyOf(activeProfiles);
        settings = List.copyOf(settings);
        evaluatedConfigurationRules = Set.copyOf(evaluatedConfigurationRules);
        problems = List.copyOf(problems);
    }

    public record Setting(String ruleId, String target, String value, String provenance) {}
}
