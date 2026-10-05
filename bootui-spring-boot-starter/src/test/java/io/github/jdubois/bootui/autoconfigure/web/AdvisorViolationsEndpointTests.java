package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.databaseadvisor.DatabaseAdvisorController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.safety.PanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.security.SecurityController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorScanState;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;

class AdvisorViolationsEndpointTests {

    private static Stream<Arguments> controllers() {
        return Stream.of(
                Arguments.of("architecture", ArchitectureController.class),
                Arguments.of("hibernate", HibernateController.class),
                Arguments.of("spring", SpringController.class),
                Arguments.of("rest-api", RestApiController.class),
                Arguments.of("memory", MemoryController.class),
                Arguments.of("security", SecurityController.class),
                Arguments.of("security", ReactiveSecurityController.class),
                Arguments.of("database-advisor", DatabaseAdvisorController.class));
    }

    private record Fixture(AdvisorViolationsEndpoint controller, String scanId) {}

    private static Fixture fixture(Class<? extends AdvisorViolationsEndpoint> type) {
        AdvisorScanState<AdvisorViolationDetailsDto> state = new AdvisorScanState<>((report, metadata) -> metadata);
        var collector = state.collector();
        collector.record("RULE", 11, java.util.Collections.nCopies(11, "safe detail"), value -> value);
        var metadata = state.publish(AdvisorViolationDetailsDto.unknown(), collector);
        AdvisorViolationsEndpoint controller = mock(type, CALLS_REAL_METHODS);
        doAnswer(invocation -> state.ruleViolations(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3)))
                .when(controller)
                .ruleViolations(any(), any(), any(), any());
        return new Fixture(controller, metadata.scanId());
    }

    @ParameterizedTest
    @MethodSource("controllers")
    void mvcRoutesSerializePagesAndCanonicalErrorsInReadOnlyMode(
            String root, Class<? extends AdvisorViolationsEndpoint> type) throws Exception {
        Fixture fixture = fixture(type);
        BootUiProperties properties = new BootUiProperties();
        properties.setReadOnly(true);
        MockMvc mvc = standaloneSetup(fixture.controller())
                .setControllerAdvice(new AdvisorViolationExceptionHandler())
                .addFilters(new PanelAccessFilter(properties))
                .build();
        String path = "/bootui/api/" + root + "/rules/RULE/violations";
        mvc.perform(get(path).param("scanId", fixture.scanId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanId").value(fixture.scanId()))
                .andExpect(jsonPath("$.ruleId").value("RULE"))
                .andExpect(jsonPath("$.violationCount").value(11))
                .andExpect(jsonPath("$.violations.length()").value(11))
                .andExpect(jsonPath("$.page.limit").value(100))
                .andExpect(jsonPath("$.page.hasMore").value(false));
        mvc.perform(get(path)
                        .param("scanId", fixture.scanId())
                        .param("offset", "10")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violations.length()").value(1));
        mvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(get(path).param("scanId", "stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(get(path.replace("/RULE/", "/missing/")).param("scanId", fixture.scanId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isString());
        for (String invalid : List.of("", "1.5", "2147483648", "not-a-number", " 1", "-1")) {
            mvc.perform(get(path).param("scanId", fixture.scanId()).param("offset", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isString());
        }
        mvc.perform(get(path).param("scanId", fixture.scanId()).param("limit", "0"))
                .andExpect(status().isBadRequest());
        BootUiProperties.Panel disabled = new BootUiProperties.Panel();
        disabled.setEnabled(false);
        properties.getPanels().put(root, disabled);
        mvc.perform(get(path).param("scanId", fixture.scanId())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("controllers")
    void webFluxRoutesHaveTheSameDefaultsStrictParsingAndReadPolicy(
            String root, Class<? extends AdvisorViolationsEndpoint> type) {
        Fixture fixture = fixture(type);
        BootUiProperties properties = new BootUiProperties();
        properties.setReadOnly(true);
        WebTestClient client = WebTestClient.bindToController(fixture.controller())
                .controllerAdvice(new AdvisorViolationExceptionHandler())
                .webFilter(new ReactivePanelAccessFilter(properties))
                .build();
        String path = "/bootui/api/" + root + "/rules/RULE/violations";
        client.get()
                .uri(path + "?scanId=" + fixture.scanId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.page.limit")
                .isEqualTo(100)
                .jsonPath("$.violations.length()")
                .isEqualTo(11);
        for (String invalid : List.of("", "1.5", "2147483648", "invalid", "-1")) {
            client.get()
                    .uri(path + "?scanId=" + fixture.scanId() + "&offset=" + invalid)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.error")
                    .isNotEmpty();
        }
        client.get().uri(path).exchange().expectStatus().isBadRequest();
        client.get()
                .uri(path + "?scanId=stale")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.error")
                .isNotEmpty();
        client.get()
                .uri(path.replace("/RULE/", "/missing/") + "?scanId=" + fixture.scanId())
                .exchange()
                .expectStatus()
                .isNotFound();
        BootUiProperties.Panel disabled = new BootUiProperties.Panel();
        disabled.setEnabled(false);
        properties.getPanels().put(root, disabled);
        client.get()
                .uri(path + "?scanId=" + fixture.scanId())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @ParameterizedTest
    @MethodSource("controllers")
    void mvcRoutesFollowTheCustomApiMount(String root, Class<? extends AdvisorViolationsEndpoint> type)
            throws Exception {
        Fixture fixture = fixture(type);
        BootUiProperties properties = new BootUiProperties();
        properties.setApiPath("/internal/diagnostics");
        MockMvc mvc = standaloneSetup(fixture.controller())
                .addPlaceholderValue("bootui.api-path", properties.getApiPath())
                .setControllerAdvice(new AdvisorViolationExceptionHandler())
                .addFilters(new PanelAccessFilter(properties))
                .build();
        mvc.perform(get("/internal/diagnostics/" + root + "/rules/RULE/violations")
                        .param("scanId", fixture.scanId()))
                .andExpect(status().isOk());
        mvc.perform(get("/bootui/api/" + root + "/rules/RULE/violations").param("scanId", fixture.scanId()))
                .andExpect(status().isNotFound());
        assertThat(fixture.controller()).isInstanceOf(type);
    }
}
