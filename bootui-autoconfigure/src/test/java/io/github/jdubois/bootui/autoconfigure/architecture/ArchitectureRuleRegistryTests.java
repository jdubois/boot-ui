package io.github.jdubois.bootui.autoconfigure.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureRuleRegistryTests {

    @Test
    void everyRuleHasACompleteAndUniqueDefinition() {
        List<ArchitectureRule> rules = ArchitectureRuleRegistry.activeRules();

        assertThat(rules).isNotEmpty();
        assertThat(rules).extracting(rule -> rule.definition().id()).doesNotHaveDuplicates();
        assertThat(rules).allSatisfy(rule -> {
            ArchitectureRuleDefinition definition = rule.definition();
            assertThat(definition.id()).isNotBlank();
            assertThat(definition.name()).isNotBlank();
            assertThat(definition.description()).isNotBlank();
            assertThat(definition.recommendation()).isNotBlank();
            assertThat(definition.category()).isNotNull();
            assertThat(definition.severity()).isIn("HIGH", "MEDIUM", "LOW", "INFO");
        });
    }

    @Test
    void activeRulesIncludeTheAdditionalSpringAndCodingPracticeChecks() {
        assertThat(ArchitectureRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .contains(
                        "ARCH-CODE-010",
                        "ARCH-CODE-011",
                        "ARCH-CODE-012",
                        "ARCH-CODE-013",
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
                        "ARCH-SPRING-016");
    }
}
