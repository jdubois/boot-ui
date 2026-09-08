package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorAssessmentEvidenceDto;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.PentestingReport;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SpringReport;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdvisorEvidenceSerializationTests {

    @ParameterizedTest
    @ValueSource(
            classes = {
                ArchitectureReport.class, MemoryReport.class, RestApiReport.class, SpringReport.class,
                DatabaseAdvisorReport.class, HibernateReport.class, SecurityReport.class, PentestingReport.class
            })
    void assessmentFactsSerializeIdenticallyAcrossJacksonGenerations(Class<?> type) throws Exception {
        var components = type.getRecordComponents();
        var signature = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = Arrays.stream(components)
                .map(component -> {
                    if (component.getType() == AdvisorAssessmentEvidenceDto.class)
                        return new AdvisorAssessmentEvidenceDto(true, true);
                    if (component.getType() == boolean.class) return true;
                    if (component.getType() == int.class) return 0;
                    if (component.getType() == List.class) return List.of();
                    return null;
                })
                .toArray();
        Object report = type.getDeclaredConstructor(signature).newInstance(values);
        String jackson2 = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report);
        String jackson3 = new tools.jackson.databind.ObjectMapper().writeValueAsString(report);
        assertThat(jackson3).isEqualTo(jackson2);
        assertThat(jackson2).contains("\"assessmentEvidence\":{\"usable\":true,\"incomplete\":true}");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dependencyCompletionIsAnAdditiveBooleanInBothAdapters(boolean complete) throws Exception {
        var dependency =
                new DependencyDto("example", "library", "1", "example:library", "test", 0, "NONE", List.of(), complete);
        String jackson2 = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dependency);
        String jackson3 = new tools.jackson.databind.ObjectMapper().writeValueAsString(dependency);
        assertThat(jackson3).isEqualTo(jackson2);
        assertThat(jackson2).contains("\"assessmentComplete\":" + complete);
    }
}
