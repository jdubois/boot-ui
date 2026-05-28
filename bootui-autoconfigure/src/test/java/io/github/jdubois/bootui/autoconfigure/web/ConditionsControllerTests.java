package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.ConditionsDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.ContextConditionsDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.MessageAndConditionDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.MessageAndConditionsDescriptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link ConditionsController}.
 *
 * <p>Covers positive matches, negative matches (no-match and partial-match
 * sub-cases), unconditional classes, exclusions, and the missing-actuator
 * empty-DTO path.  {@link ConditionsDescriptor} and
 * {@link ContextConditionsDescriptor} are {@code final} and use
 * {@link MockMakers#INLINE}; the non-final message descriptors use the
 * default mock maker.</p>
 */
class ConditionsControllerTests {

    private static <T> T inlineMock(Class<T> cls) {
        return mock(cls, withSettings().mockMaker(MockMakers.INLINE));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ConditionsReportEndpoint> providerOf(ConditionsReportEndpoint endpoint) {
        ObjectProvider<ConditionsReportEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(endpoint);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ConditionsReportEndpoint> emptyProvider() {
        ObjectProvider<ConditionsReportEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void conditionsReturnsEmptyReportWhenActuatorUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new ConditionsController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/conditions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positiveMatches").isArray())
                .andExpect(jsonPath("$.positiveMatches").isEmpty())
                .andExpect(jsonPath("$.negativeMatches").isArray())
                .andExpect(jsonPath("$.negativeMatches").isEmpty())
                .andExpect(jsonPath("$.unconditionalClasses").isArray())
                .andExpect(jsonPath("$.unconditionalClasses").isEmpty())
                .andExpect(jsonPath("$.exclusions").isArray())
                .andExpect(jsonPath("$.exclusions").isEmpty());
    }

    @Test
    void conditionsReturnsPositiveMatches() throws Exception {
        MessageAndConditionDescriptor matchDesc = mock(MessageAndConditionDescriptor.class);
        when(matchDesc.getCondition()).thenReturn("OnClassCondition");
        when(matchDesc.getMessage()).thenReturn("@ConditionalOnClass found required class 'javax.servlet.Servlet'");

        ContextConditionsDescriptor ccd = inlineMock(ContextConditionsDescriptor.class);
        when(ccd.getPositiveMatches()).thenReturn(Map.of("org.example.WebConfig", List.of(matchDesc)));
        when(ccd.getNegativeMatches()).thenReturn(Map.of());
        when(ccd.getUnconditionalClasses()).thenReturn(Set.of());
        when(ccd.getExclusions()).thenReturn(List.of());

        ConditionsDescriptor descriptor = inlineMock(ConditionsDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("application", ccd));

        ConditionsReportEndpoint endpoint = mock(ConditionsReportEndpoint.class);
        when(endpoint.conditions()).thenReturn(descriptor);

        MockMvc mvc =
                standaloneSetup(new ConditionsController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/conditions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.positiveMatches[0].autoConfigurationClass").value("org.example.WebConfig"))
                .andExpect(jsonPath("$.positiveMatches[0].condition").value("OnClassCondition"))
                .andExpect(jsonPath("$.positiveMatches[0].outcome").value("MATCH"))
                .andExpect(jsonPath("$.negativeMatches").isEmpty());
    }

    @Test
    void conditionsReturnsNegativeNoMatchAndPartialMatch() throws Exception {
        MessageAndConditionDescriptor noMatchDesc = mock(MessageAndConditionDescriptor.class);
        when(noMatchDesc.getCondition()).thenReturn("OnBeanCondition");
        when(noMatchDesc.getMessage()).thenReturn("@ConditionalOnBean (types: 'DataSource') did not find any beans");

        MessageAndConditionDescriptor partialDesc = mock(MessageAndConditionDescriptor.class);
        when(partialDesc.getCondition()).thenReturn("OnPropertyCondition");
        when(partialDesc.getMessage()).thenReturn("@ConditionalOnProperty (spring.datasource.url) matched");

        MessageAndConditionsDescriptor negEntry = mock(MessageAndConditionsDescriptor.class);
        when(negEntry.getNotMatched()).thenReturn(List.of(noMatchDesc));
        when(negEntry.getMatched()).thenReturn(List.of(partialDesc));

        ContextConditionsDescriptor ccd = inlineMock(ContextConditionsDescriptor.class);
        when(ccd.getPositiveMatches()).thenReturn(Map.of());
        when(ccd.getNegativeMatches()).thenReturn(Map.of("org.example.JpaConfig", negEntry));
        when(ccd.getUnconditionalClasses()).thenReturn(Set.of());
        when(ccd.getExclusions()).thenReturn(List.of());

        ConditionsDescriptor descriptor = inlineMock(ConditionsDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("application", ccd));

        ConditionsReportEndpoint endpoint = mock(ConditionsReportEndpoint.class);
        when(endpoint.conditions()).thenReturn(descriptor);

        MockMvc mvc =
                standaloneSetup(new ConditionsController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/conditions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // noMatchDesc → negativeMatches with outcome NO_MATCH
                .andExpect(
                        jsonPath("$.negativeMatches[0].autoConfigurationClass").value("org.example.JpaConfig"))
                .andExpect(jsonPath("$.negativeMatches[0].condition").value("OnBeanCondition"))
                .andExpect(jsonPath("$.negativeMatches[0].outcome").value("NO_MATCH"))
                // partialDesc → positiveMatches with outcome PARTIAL
                .andExpect(
                        jsonPath("$.positiveMatches[0].autoConfigurationClass").value("org.example.JpaConfig"))
                .andExpect(jsonPath("$.positiveMatches[0].condition").value("OnPropertyCondition"))
                .andExpect(jsonPath("$.positiveMatches[0].outcome").value("PARTIAL"));
    }

    @Test
    void conditionsReturnsUnconditionalClassesAndExclusions() throws Exception {
        ContextConditionsDescriptor ccd = inlineMock(ContextConditionsDescriptor.class);
        when(ccd.getPositiveMatches()).thenReturn(Map.of());
        when(ccd.getNegativeMatches()).thenReturn(Map.of());
        when(ccd.getUnconditionalClasses()).thenReturn(Set.of("org.example.UnconditionalConfig"));
        when(ccd.getExclusions()).thenReturn(List.of("org.example.ExcludedAutoConfig"));

        ConditionsDescriptor descriptor = inlineMock(ConditionsDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("application", ccd));

        ConditionsReportEndpoint endpoint = mock(ConditionsReportEndpoint.class);
        when(endpoint.conditions()).thenReturn(descriptor);

        MockMvc mvc =
                standaloneSetup(new ConditionsController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/conditions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unconditionalClasses[0]").value("org.example.UnconditionalConfig"))
                .andExpect(jsonPath("$.exclusions[0]").value("org.example.ExcludedAutoConfig"));
    }

    @Test
    void conditionsSupportsOutcomeFilteringAndPaging() throws Exception {
        MessageAndConditionDescriptor alpha = mock(MessageAndConditionDescriptor.class);
        when(alpha.getCondition()).thenReturn("OnClassCondition");
        when(alpha.getMessage()).thenReturn("alpha matched");

        MessageAndConditionDescriptor beta = mock(MessageAndConditionDescriptor.class);
        when(beta.getCondition()).thenReturn("OnBeanCondition");
        when(beta.getMessage()).thenReturn("beta matched");

        ContextConditionsDescriptor ccd = inlineMock(ContextConditionsDescriptor.class);
        when(ccd.getPositiveMatches())
                .thenReturn(Map.of(
                        "org.example.AlphaConfig", List.of(alpha),
                        "org.example.BetaConfig", List.of(beta)));
        when(ccd.getNegativeMatches()).thenReturn(Map.of());
        when(ccd.getUnconditionalClasses()).thenReturn(Set.of());
        when(ccd.getExclusions()).thenReturn(List.of());

        ConditionsDescriptor descriptor = inlineMock(ConditionsDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("application", ccd));

        ConditionsReportEndpoint endpoint = mock(ConditionsReportEndpoint.class);
        when(endpoint.conditions()).thenReturn(descriptor);

        MockMvc mvc =
                standaloneSetup(new ConditionsController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/conditions")
                        .param("outcome", "positive")
                        .param("q", "Config")
                        .param("offset", "1")
                        .param("limit", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positiveMatches.length()").value(1))
                .andExpect(
                        jsonPath("$.positiveMatches[0].autoConfigurationClass").value("org.example.BetaConfig"))
                .andExpect(jsonPath("$.negativeMatches").isEmpty())
                .andExpect(jsonPath("$.page.total").value(2))
                .andExpect(jsonPath("$.page.matched").value(2))
                .andExpect(jsonPath("$.page.offset").value(1))
                .andExpect(jsonPath("$.page.returned").value(1))
                .andExpect(jsonPath("$.counts.positiveTotal").value(2))
                .andExpect(jsonPath("$.counts.positiveMatched").value(2));
    }
}
