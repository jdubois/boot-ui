package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.web.FaultToleranceController;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyMetricsDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicySettingDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceService;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.spi.FaultTolerancePolicyProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the read-only {@code /bootui/api/fault-tolerance} contract: the report's shape is stable, the panel
 * fails closed with an explicit reason when no library is present, and no mutating verb is exposed at all.
 */
class FaultToleranceControllerTests {

    private static MockMvc mvc(FaultToleranceService service) {
        return standaloneSetup(new FaultToleranceController(service)).build();
    }

    private static FaultTolerancePolicyProvider provider(FaultTolerancePolicyDto... policies) {
        return new FaultTolerancePolicyProvider() {
            @Override
            public String providerId() {
                return FaultToleranceVocabulary.PROVIDER_RESILIENCE4J;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<FaultTolerancePolicyDto> policies() {
                return List.of(policies);
            }
        };
    }

    @Test
    void failsClosedWithAnExplicitReasonWhenNoFaultToleranceLibraryIsPresent() throws Exception {
        mvc(new FaultToleranceService(List.of(), null, 200))
                .perform(get("/bootui/api/fault-tolerance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faultTolerancePresent").value(false))
                .andExpect(jsonPath("$.unavailableReason").isNotEmpty())
                .andExpect(jsonPath("$.totalPolicies").value(0))
                .andExpect(jsonPath("$.policies").isEmpty())
                .andExpect(jsonPath("$.events").isEmpty())
                .andExpect(jsonPath("$.maxEvents").value(200));
    }

    @Test
    void servesTheStablePolicyAndEventShape() throws Exception {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 50);
        recorder.record(
                "payments",
                FaultToleranceVocabulary.TYPE_RETRY,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                "PayClient#charge",
                FaultToleranceVocabulary.OUTCOME_RETRY,
                2,
                17L,
                "IOException");
        FaultTolerancePolicyDto policy = new FaultTolerancePolicyDto(
                "payments",
                FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                FaultToleranceVocabulary.SOURCE_REGISTRY,
                "PayClient#charge",
                FaultToleranceVocabulary.STATE_CLOSED,
                List.of(new FaultTolerancePolicySettingDto(
                        "failureRateThreshold", "50%", FaultToleranceVocabulary.PROVENANCE_DEFAULT)),
                new FaultTolerancePolicyMetricsDto(4L, 1L, null, null, null, null, 20.0, 5L));

        mvc(new FaultToleranceService(List.of(provider(policy)), recorder, 200))
                .perform(get("/bootui/api/fault-tolerance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faultTolerancePresent").value(true))
                .andExpect(jsonPath("$.captureEnabled").value(true))
                .andExpect(jsonPath("$.providers[0]").value(FaultToleranceVocabulary.PROVIDER_RESILIENCE4J))
                .andExpect(jsonPath("$.totalPolicies").value(1))
                .andExpect(jsonPath("$.policyCountsByType.CIRCUIT_BREAKER").value(1))
                .andExpect(jsonPath("$.policies[0].name").value("payments"))
                .andExpect(jsonPath("$.policies[0].state").value("CLOSED"))
                .andExpect(jsonPath("$.policies[0].target").value("PayClient#charge"))
                .andExpect(jsonPath("$.policies[0].settings[0].name").value("failureRateThreshold"))
                .andExpect(jsonPath("$.policies[0].settings[0].provenance").value("DEFAULT"))
                .andExpect(jsonPath("$.policies[0].metrics.successfulCalls").value(4))
                .andExpect(jsonPath("$.policies[0].metrics.failureRatePercent").value(20.0))
                .andExpect(jsonPath("$.events[0].policyName").value("payments"))
                .andExpect(jsonPath("$.events[0].outcome").value("RETRY"))
                .andExpect(jsonPath("$.events[0].attempt").value(2))
                .andExpect(jsonPath("$.events[0].durationMillis").value(17))
                .andExpect(jsonPath("$.events[0].failureCategory").value("IOException"));
    }

    @Test
    void reportsCaptureDisabledWhileStillServingPolicies() throws Exception {
        FaultTolerancePolicyDto policy = new FaultTolerancePolicyDto(
                "payments",
                FaultToleranceVocabulary.TYPE_RETRY,
                FaultToleranceVocabulary.PROVIDER_SPRING_RETRY,
                FaultToleranceVocabulary.SOURCE_ANNOTATION,
                null,
                null,
                List.of(),
                FaultTolerancePolicyMetricsDto.none());

        mvc(new FaultToleranceService(List.of(provider(policy)), new FaultToleranceEventRecorder(false, 50), 200))
                .perform(get("/bootui/api/fault-tolerance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureEnabled").value(false))
                .andExpect(jsonPath("$.totalPolicies").value(1))
                .andExpect(jsonPath("$.events").isEmpty());
    }

    @Test
    void exposesNoMutatingVerbBecauseThePanelIsStrictlyCaptureOnly() throws Exception {
        MockMvc mvc = mvc(new FaultToleranceService(List.of(), null, 200));

        mvc.perform(post("/bootui/api/fault-tolerance")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/bootui/api/fault-tolerance")).andExpect(status().isMethodNotAllowed());
    }
}
