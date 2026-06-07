package io.github.jdubois.bootui.autoconfigure.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RestApiRuleRegistryTests {

    @Test
    void registersThirtyRulesWithUniqueIds() {
        List<RestApiRule> rules = RestApiRuleRegistry.activeRules();

        assertThat(rules).hasSize(30);

        List<String> ids = rules.stream().map(rule -> rule.definition().id()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allMatch(id -> id.startsWith("RAPI-"));
    }

    @Test
    void everyRuleHasCompleteMetadata() {
        Set<String> severities = Set.of("HIGH", "MEDIUM", "LOW", "INFO");

        for (RestApiRule rule : RestApiRuleRegistry.activeRules()) {
            RestApiRuleDefinition definition = rule.definition();
            assertThat(definition.name()).isNotBlank();
            assertThat(definition.description()).isNotBlank();
            assertThat(definition.recommendation()).isNotBlank();
            assertThat(definition.category()).isNotNull();
            assertThat(definition.severity()).isIn(severities);
        }
    }
}
