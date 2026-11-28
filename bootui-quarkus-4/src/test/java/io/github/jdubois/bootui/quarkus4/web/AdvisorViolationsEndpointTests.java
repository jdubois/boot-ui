package io.github.jdubois.bootui.quarkus4.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorScanState;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class AdvisorViolationsEndpointTests {

    private static Stream<Class<? extends AdvisorViolationsEndpoint>> resources() {
        return Stream.of(
                ArchitectureResource.class,
                HibernateResource.class,
                SpringResource.class,
                RestApiResource.class,
                MemoryResource.class,
                SecurityResource.class,
                DatabaseAdvisorResource.class);
    }

    @ParameterizedTest
    @MethodSource("resources")
    void allResourcesShareStrictQueriesAndJacksonPageContract(Class<? extends AdvisorViolationsEndpoint> type) {
        AdvisorScanState<AdvisorViolationDetailsDto> state = new AdvisorScanState<>((report, metadata) -> metadata);
        var collector = state.collector();
        collector.record("RULE", 11, java.util.Collections.nCopies(11, "safe detail"), value -> value);
        var metadata = state.publish(AdvisorViolationDetailsDto.unknown(), collector);
        AdvisorViolationsEndpoint resource = mock(type, CALLS_REAL_METHODS);
        doAnswer(invocation -> state.ruleViolations(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)))
                .when(resource)
                .ruleViolations(any(), any(), any(), any());

        var page = resource.violations("RULE", metadata.scanId(), null, null);
        var json = new ObjectMapper().valueToTree(page);
        assertThat(json.path("scanId").asString()).isEqualTo(metadata.scanId());
        assertThat(json.path("ruleId").asString()).isEqualTo("RULE");
        assertThat(json.path("violationCount").asInt()).isEqualTo(11);
        assertThat(json.path("retainedCount").asInt()).isEqualTo(11);
        assertThat(json.path("violations").size()).isEqualTo(11);
        assertThat(json.path("page").path("limit").asInt()).isEqualTo(100);
        assertThat(resource.violations("RULE", metadata.scanId(), "10", "1").violations())
                .hasSize(1);
        for (String invalid : List.of("", "1.5", "2147483648", "invalid", " 1", "-1")) {
            assertThatThrownBy(() -> resource.violations("RULE", metadata.scanId(), invalid, null))
                    .isInstanceOf(AdvisorViolationException.class);
        }
        assertThatThrownBy(() -> resource.violations("RULE", null, null, null))
                .isInstanceOf(AdvisorViolationException.class);
        assertThatThrownBy(() -> resource.violations("RULE", "stale", null, null))
                .isInstanceOf(AdvisorViolationException.class);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 404, 409})
    void canonicalErrorMapperPreservesStatusAndSafeJson(int status) {
        var exception = new AdvisorViolationException(status, "Safe advisor message.");
        try (var response = new AdvisorViolationExceptionMapper().toResponse(exception)) {
            assertThat(response.getStatus()).isEqualTo(status);
            assertThat(response.getMediaType().toString()).isEqualTo("application/json");
            assertThat(response.getEntity()).isEqualTo(Map.of("error", "Safe advisor message."));
        }
    }
}
