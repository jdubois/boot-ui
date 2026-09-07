package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.core.dto.ArchitectureScanStatusDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArchitectureResourceTests {

    @ParameterizedTest
    @ValueSource(strings = {"ERROR", "PARTIAL"})
    void preservesFailureStatusAndErrorsAcrossScanCacheAndJacksonSerialization(String status) {
        ArchitectureScanner scanner = mock(ArchitectureScanner.class);
        DismissedRulesStore dismissedRules = mock(DismissedRulesStore.class);
        ArchitectureRuleResultDto error = new ArchitectureRuleResultDto(
                "ARCH-CODE-012",
                "Logger rule",
                "Coding practices",
                "LOW",
                "description",
                "ERROR",
                0,
                List.of("Rule could not be evaluated (LinkageError)."),
                "recommendation",
                null);
        ArchitectureReport report = new ArchitectureReport(
                true,
                "disclaimer",
                List.of("com.example"),
                1,
                1,
                0,
                List.of(),
                new ArchitectureScanStatusDto("BootUI ArchUnit hygiene", status, "message", 1L, 1, 1, 0),
                List.of(),
                List.of(error));
        when(scanner.initialReport()).thenReturn(report);
        when(scanner.scan()).thenReturn(report);
        when(scanner.applyDismissals(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dismissedRules.load()).thenReturn(Set.of());
        ArchitectureResource resource = new ArchitectureResource(scanner, dismissedRules);

        assertThat(resource.scan()).isSameAs(report);
        ArchitectureReport cached = resource.architecture();
        assertThat(cached).isSameAs(report);
        JsonNode json = new ObjectMapper().valueToTree(cached);
        assertThat(json.path("scan").path("status").asText()).isEqualTo(status);
        assertThat(json.path("analysisErrors").get(0).path("status").asText()).isEqualTo("ERROR");
        assertThat(json.path("analysisErrors").get(0).path("id").asText()).isEqualTo("ARCH-CODE-012");
        verify(scanner).scan();
    }
}
