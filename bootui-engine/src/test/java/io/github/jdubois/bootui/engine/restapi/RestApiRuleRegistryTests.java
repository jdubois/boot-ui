package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RestApiRuleRegistryTests {

    @Test
    void registersFiftySixRulesWithUniqueIds() {
        List<RestApiRule> rules = RestApiRuleRegistry.activeRules();

        assertThat(rules).hasSize(56);

        List<String> ids = rules.stream().map(rule -> rule.definition().id()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allMatch(id -> id.startsWith("RAPI-"));
        assertThat(ids)
                .contains(
                        "RAPI-MAP-008",
                        "RAPI-MAP-009",
                        "RAPI-MAP-010",
                        "RAPI-MAP-011",
                        "RAPI-RESP-008",
                        "RAPI-RESP-009",
                        "RAPI-VER-005",
                        "RAPI-VER-006",
                        "RAPI-ERR-005",
                        "RAPI-ERR-006",
                        "RAPI-ERR-007",
                        "RAPI-ERR-008",
                        "RAPI-ERR-009",
                        "RAPI-ERR-010",
                        "RAPI-ERR-011",
                        "RAPI-VALID-004",
                        "RAPI-VALID-005",
                        "RAPI-DTO-005",
                        "RAPI-DOC-003",
                        "RAPI-PAGE-003",
                        "RAPI-NAME-004");
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

    @Test
    void retiredDefinitionsKeepTheirIdsWithoutEmittingFindings() {
        Set<String> retired = Set.of("RAPI-MAP-008", "RAPI-NAME-004", "RAPI-ERR-011", "RAPI-DOC-003");
        RestApiContext empty = new RestApiContext(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                false,
                false,
                List.of(),
                List.of(),
                RestApiModel.Framework.SPRING);
        List<RestApiRule> rules = RestApiRuleRegistry.activeRules();
        assertThat(rules.stream()
                        .filter(rule -> retired.contains(rule.definition().id())))
                .hasSize(4);
        for (RestApiRule rule : rules) {
            if (retired.contains(rule.definition().id())) {
                var result = rule.evaluate(empty);
                assertThat(result.id()).isEqualTo(rule.definition().id());
                assertThat(result.status()).isEqualTo("SKIPPED");
                assertThat(result.violationCount()).isZero();
            }
        }
        assertThat(rules.stream()
                        .filter(rule -> !retired.contains(rule.definition().id())))
                .hasSize(52);
    }

    @Test
    void weakDeclarationSignalsHaveCalibratedSeverity() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("RAPI-MAP-003", "LOW"), Map.entry("RAPI-MAP-005", "INFO"),
                Map.entry("RAPI-MAP-010", "INFO"), Map.entry("RAPI-NAME-001", "INFO"),
                Map.entry("RAPI-NAME-003", "INFO"), Map.entry("RAPI-RESP-001", "LOW"),
                Map.entry("RAPI-RESP-008", "INFO"), Map.entry("RAPI-RESP-009", "INFO"),
                Map.entry("RAPI-VALID-001", "LOW"), Map.entry("RAPI-DTO-002", "LOW"),
                Map.entry("RAPI-VER-003", "INFO"), Map.entry("RAPI-ERR-001", "INFO"));
        for (RestApiRule rule : RestApiRuleRegistry.activeRules()) {
            if (expected.containsKey(rule.definition().id())) {
                assertThat(rule.definition().severity())
                        .as(rule.definition().id())
                        .isEqualTo(expected.get(rule.definition().id()));
            }
        }
    }

    @Test
    void paginationVocabularyRuleUsesTheDocumentedGuideline() {
        RestApiRule paginationVocabularyRule = RestApiRuleRegistry.activeRules().stream()
                .filter(rule -> rule.definition().id().equals("RAPI-PAGE-003"))
                .findFirst()
                .orElseThrow();

        assertThat(paginationVocabularyRule.definition().learnMoreUrl())
                .isEqualTo("https://opensource.zalando.com/restful-api-guidelines/#pagination");
    }
}
