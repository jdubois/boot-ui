package io.github.jdubois.bootui.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AdvisorEvidenceDtoTests {
    @Test
    void limitationsAreImmutableBoundedAndControlCharactersAreRemoved() {
        List<String> limitations = new ArrayList<>(List.of("Unknown\nobservation", "x".repeat(300)));
        AdvisorEvidenceDto evidence = new AdvisorEvidenceDto(true, false, limitations);
        limitations.clear();
        assertThat(evidence.limitations()).containsExactly("Unknown observation", "x".repeat(240));
        assertThatThrownBy(() -> evidence.limitations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new AdvisorEvidenceDto(
                                false,
                                false,
                                IntStream.range(0, 30)
                                        .mapToObj(Integer::toString)
                                        .toList())
                        .limitations())
                .hasSize(20);
    }

    @Test
    void compatibilityConstructorsNeverInventCompletedEvidence() {
        DependencyDto dependency = new DependencyDto("g", "a", "1", "g:a", "test", 0, "NONE", List.of());
        DependenciesReport report = new DependenciesReport(true, 1, 0, List.of(), null, null, List.of(dependency));
        assertThat(dependency.assessment()).isEqualTo(new DependencyAssessmentDto(false, false));
        assertThat(report.evidence()).isEqualTo(AdvisorEvidenceDto.unknown());
        assertThat(new MemoryReport(true, "", 20, 0, null, List.of(), null, List.of(), List.of())
                        .evidence()
                        .usable())
                .isFalse();
    }

    @Test
    void invalidDependencyCompletionIsRejectedAndMissingEvidenceIsUnknown() {
        assertThatThrownBy(() -> new DependencyAssessmentDto(false, true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AdvisorEvidenceDto(false, false, null).limitations()).isEmpty();
        assertThat(AdvisorEvidenceDto.unknown().coverageComplete()).isFalse();
        assertThat(AdvisorEvidenceDto.unknown().usable()).isFalse();
    }

    @Test
    void usabilityIsIndependentOfCoverageAndUsesBooleanWireFields() {
        AdvisorEvidenceDto evidence = new AdvisorEvidenceDto(true, false, List.of("Partial evidence"));
        assertThat(evidence.usable()).isTrue();
        assertThat(evidence.coverageComplete()).isFalse();
        AdvisorEvidenceDto empty = new AdvisorEvidenceDto(false, true, List.of());
        assertThat(empty.usable()).isFalse();
        assertThat(empty.coverageComplete()).isTrue();
        assertThat(AdvisorEvidenceDto.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("usable", "coverageComplete", "limitations");
        assertThat(AdvisorEvidenceDto.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(boolean.class, boolean.class, List.class);
        assertThat(AdvisorEvidenceDto.class.getAnnotations()).isEmpty();
    }
}
