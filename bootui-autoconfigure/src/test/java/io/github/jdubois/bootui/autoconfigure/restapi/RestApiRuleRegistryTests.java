package io.github.jdubois.bootui.autoconfigure.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RestApiRuleRegistryTests {

    @Test
    void registersFortySevenRulesWithUniqueIds() {
        List<RestApiRule> rules = RestApiRuleRegistry.activeRules();

        assertThat(rules).hasSize(47);

        List<String> ids = rules.stream().map(rule -> rule.definition().id()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allMatch(id -> id.startsWith("RAPI-"));
        assertThat(ids).contains(
                "RAPI-MAP-008", "RAPI-MAP-009", "RAPI-MAP-010", "RAPI-MAP-011",
                "RAPI-RESP-008", "RAPI-VER-005", "RAPI-VER-006",
                "RAPI-ERR-005", "RAPI-ERR-006",
                "RAPI-VALID-004", "RAPI-DTO-005", "RAPI-NAME-004");
        assertThat(ids).doesNotContain("RAPI-DTO-003");
    }

    @Test
    void everyRuleHasCompleteMetadata() {
        Set<String> severities = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

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
