package io.github.jdubois.bootui.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AdvisorChangesDtoTests {

    @Test
    void theOlderDetailConstructorsStillCompileAndCarryNoChanges() {
        assertThat(new AdvisorViolationDetailsDto("scan", 1, 1, 10, false).changes())
                .isNull();
        assertThat(new AdvisorViolationDetailsDto("scan", 1, 1, 10, false, List.of("note")).changes())
                .isNull();
        assertThat(AdvisorViolationDetailsDto.unknown().changes()).isNull();
    }

    @Test
    void theOlderPageConstructorsStillCompileAndCarryNoIdentitiesOrChanges() {
        PageMetadata page = new PageMetadata(1, 1, 0, 100, 1, false);
        AdvisorRuleViolationsDto seven = new AdvisorRuleViolationsDto("scan", "R", 1, 1, false, List.of("a"), page);
        AdvisorRuleViolationsDto eight =
                new AdvisorRuleViolationsDto("scan", "R", 1, 1, false, List.of("a"), page, List.of());
        assertThat(seven).isEqualTo(eight);
        assertThat(seven.identities()).isEmpty();
        assertThat(seven.changes()).isEmpty();
    }

    @Test
    void identitiesAndChangesAreAlignedWithTheViolationsOrAbsent() {
        PageMetadata page = new PageMetadata(2, 2, 0, 100, 2, false);
        List<String> identities = new ArrayList<>(java.util.Arrays.asList("v1:a", null));
        AdvisorRuleViolationsDto dto = new AdvisorRuleViolationsDto(
                "scan", "R", 2, 2, false, List.of("a", "b"), page, List.of(), identities, List.of());
        identities.add("mutated");
        assertThat(dto.identities()).containsExactly("v1:a", null);
        assertThat(dto.changes()).isEmpty();
        assertThatThrownBy(() -> new AdvisorRuleViolationsDto(
                        "scan", "R", 2, 2, false, List.of("a", "b"), page, List.of(), List.of("v1:a"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AdvisorRuleViolationsDto(
                                "scan",
                                "R",
                                2,
                                2,
                                false,
                                List.of("a", "b"),
                                page,
                                List.of(),
                                java.util.Arrays.asList(null, null),
                                List.of())
                        .identities())
                .isEmpty();
    }

    @Test
    void theIssueEightArgumentConstructorDefaultsTheExtensions() {
        AdvisorChangesDto dto = new AdvisorChangesDto("prev", true, 1, 2, 3, 0, List.of(), List.of());
        assertThat(dto.unverifiedCount()).isZero();
        assertThat(dto.rules()).isEmpty();
    }

    @Test
    void resolvedFindingsRulesAndNotesAreBounded() {
        List<AdvisorResolvedFindingDto> resolved = IntStream.range(0, 80)
                .mapToObj(i -> new AdvisorResolvedFindingDto("R", "x".repeat(500)))
                .toList();
        List<AdvisorRuleChangeDto> rules = IntStream.range(0, 150)
                .mapToObj(i -> new AdvisorRuleChangeDto("R" + i, 1, 0, 0))
                .toList();
        List<String> notes =
                IntStream.range(0, 30).mapToObj(i -> "note " + i + "\n" + "y".repeat(400)).toList();

        AdvisorChangesDto dto = new AdvisorChangesDto("prev", false, 0, 80, 0, 0, resolved, notes, 0, rules);

        assertThat(dto.resolved()).hasSize(AdvisorChangesDto.MAX_RESOLVED);
        assertThat(dto.resolved().get(0).text()).hasSize(240);
        assertThat(dto.rules()).hasSize(AdvisorChangesDto.MAX_RULES);
        assertThat(dto.notes()).hasSize(10).allSatisfy(note -> assertThat(note).hasSizeLessThanOrEqualTo(240));
        assertThat(dto.notes().get(0)).doesNotContain("\n");
    }
}
