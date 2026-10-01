package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchitectureRuleRegistryTests {

    @Test
    void everyRuleHasACompleteAndUniqueDefinition() {
        List<ArchitectureRule> rules = ArchitectureRuleRegistry.activeRules();

        assertThat(rules).hasSize(40);
        assertThat(rules).extracting(rule -> rule.definition().id()).doesNotHaveDuplicates();
        assertThat(rules).allSatisfy(rule -> {
            ArchitectureRuleDefinition definition = rule.definition();
            assertThat(definition.id()).isNotBlank();
            assertThat(definition.name()).isNotBlank();
            assertThat(definition.description()).isNotBlank();
            assertThat(definition.recommendation()).isNotBlank();
            assertThat(definition.category()).isNotNull();
            assertThat(definition.severity()).isIn("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
            assertThat(definition.learnMoreUrl())
                    .as("learnMoreUrl for %s", definition.id())
                    .isNotBlank()
                    .startsWith("https://");
        });
    }

    @Test
    void activeRulesIncludeTheAdditionalSpringAndCodingPracticeChecks() {
        assertThat(ArchitectureRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .contains(
                        "ARCH-CODE-010",
                        "ARCH-CODE-012",
                        "ARCH-CODE-013",
                        "ARCH-CODE-016",
                        "ARCH-SPRING-006",
                        "ARCH-SPRING-007",
                        "ARCH-SPRING-008",
                        "ARCH-SPRING-009",
                        "ARCH-SPRING-010",
                        "ARCH-SPRING-011",
                        "ARCH-SPRING-012",
                        "ARCH-SPRING-013",
                        "ARCH-SPRING-014",
                        "ARCH-CODE-014",
                        "ARCH-CODE-015",
                        "ARCH-SPRING-015",
                        "ARCH-SPRING-017",
                        "ARCH-SPRING-018",
                        "ARCH-SPRING-019",
                        "ARCH-SPRING-020",
                        "ARCH-SPRING-021",
                        "ARCH-SPRING-022",
                        "ARCH-SPRING-023",
                        "ARCH-SPRING-024",
                        "ARCH-CODE-017",
                        "ARCH-CODE-018",
                        "ARCH-MOD-001");
    }

    @Test
    void retiredRuleIdsAreNeverRegisteredAgain() {
        assertThat(ArchitectureRuleRegistry.RETIRED_RULE_IDS)
                .containsExactlyInAnyOrder("ARCH-CODE-005", "ARCH-CODE-011", "ARCH-SPRING-005", "ARCH-SPRING-016");
        assertThat(ArchitectureRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .doesNotContainAnyElementsOf(ArchitectureRuleRegistry.RETIRED_RULE_IDS);
    }

    @Test
    void severitiesReflectTheAuditedImpact() {
        Map<String, String> expected = Map.of(
                "ARCH-CODE-007", "MEDIUM",
                "ARCH-CODE-010", "INFO",
                "ARCH-CODE-016", "MEDIUM",
                "ARCH-SPRING-002", "LOW",
                "ARCH-SPRING-011", "HIGH",
                "ARCH-SPRING-023", "HIGH",
                "ARCH-SPRING-024", "HIGH");
        assertThat(ArchitectureRuleRegistry.activeRules())
                .filteredOn(rule -> expected.containsKey(rule.definition().id()))
                .hasSize(expected.size())
                .allSatisfy(rule -> assertThat(rule.definition().severity())
                        .as(rule.definition().id())
                        .isEqualTo(expected.get(rule.definition().id())));
    }
}
