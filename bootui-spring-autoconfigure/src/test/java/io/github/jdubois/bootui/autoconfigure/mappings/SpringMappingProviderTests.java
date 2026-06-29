package io.github.jdubois.bootui.autoconfigure.mappings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ContextMappingsDescriptor;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDescription;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDetails;
import org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription;
import org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription.MediaTypeExpressionDescription;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Tests for {@link SpringMappingProvider}, the single Actuator touch-point that flattens the raw
 * mappings descriptor and applies BootUI's self-data filter.
 *
 * <p>This is the riskiest code moved out of the old {@code MappingsController}, so it is covered
 * directly here (the controller test now mocks the engine service). In particular,
 * {@link #excludesMappingWhenRawPredicateIsBootUiEvenIfTheFlattenedPatternIsNot()} pins the behavior
 * that would be lost if the self-filter were pushed into the engine as a {@code Predicate<MappingDto>}:
 * the filter inspects the raw predicate string, which no longer exists once a row is flattened to a
 * {@link MappingDto}.</p>
 */
class SpringMappingProviderTests {

    private final BootUiSelfDataFilter selfDataFilter = BootUiSelfDataFilter.defaults();

    @Test
    void availableReflectsEndpointPresenceAndMappingsAreEmptyWhenAbsent() {
        SpringMappingProvider absent = new SpringMappingProvider(() -> null, selfDataFilter);
        assertThat(absent.available()).isFalse();
        assertThat(absent.mappings()).isEmpty();

        MappingsEndpoint endpoint = mock(MappingsEndpoint.class);
        SpringMappingProvider present = new SpringMappingProvider(() -> endpoint, selfDataFilter);
        assertThat(present.available()).isTrue();
    }

    @Test
    void flattensPredicateOnlyDescriptionsWithAnyMethod() {
        DispatcherServletMappingDescription alpha = predicateOnly("/alpha", "org.example.AlphaController#alpha");
        DispatcherServletMappingDescription beta = predicateOnly("/beta", "org.example.BetaController#beta");
        SpringMappingProvider provider =
                new SpringMappingProvider(() -> endpointReturning(alpha, beta), selfDataFilter);

        assertThat(provider.mappings())
                .extracting(MappingDto::method, MappingDto::pattern, MappingDto::handler)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("ANY", "/alpha", "org.example.AlphaController#alpha"),
                        Tuple.tuple("ANY", "/beta", "org.example.BetaController#beta"));
    }

    @Test
    void hidesBootUiEndpointsByHandlerButKeepsApplicationEndpoints() {
        DispatcherServletMappingDescription bootUi =
                predicateOnly("/bootui/api/beans", "io.github.jdubois.bootui.autoconfigure.web.BeansController#beans");
        DispatcherServletMappingDescription sample =
                predicateOnly("/sample", "io.github.jdubois.bootui.sample.SampleController#sample");
        SpringMappingProvider provider =
                new SpringMappingProvider(() -> endpointReturning(bootUi, sample), selfDataFilter);

        assertThat(provider.mappings()).extracting(MappingDto::pattern).containsExactly("/sample");
    }

    @Test
    void excludesMappingWhenRawPredicateIsBootUiEvenIfTheFlattenedPatternIsNot() {
        // The flattened pattern (/app) is NOT a BootUI path, but the raw predicate string mentions a
        // /bootui path. Today's self-filter inspects the raw predicate and excludes the whole mapping; a
        // DTO-only filter (seeing only pattern=/app) would wrongly keep it. This pins the byte-identical
        // behavior that justifies keeping the self-filter in the adapter.
        DispatcherServletMappingDescription leaky = conditioned(
                Set.of("/app"),
                Set.of(RequestMethod.GET),
                "{GET [/app], also touches [/bootui/api/secret]}",
                "org.example.AppController#app",
                null,
                null);
        SpringMappingProvider provider = new SpringMappingProvider(() -> endpointReturning(leaky), selfDataFilter);
        assertThat(provider.mappings()).isEmpty();

        // Control: same shape but a non-BootUI predicate -> the /app mapping is kept.
        DispatcherServletMappingDescription kept = conditioned(
                Set.of("/app"), Set.of(RequestMethod.GET), "{GET [/app]}", "org.example.AppController#app", null, null);
        SpringMappingProvider keptProvider = new SpringMappingProvider(() -> endpointReturning(kept), selfDataFilter);
        assertThat(keptProvider.mappings())
                .extracting(MappingDto::method, MappingDto::pattern)
                .containsExactly(Tuple.tuple("GET", "/app"));
    }

    @Test
    void sortsMethodsAndJoinsProducesAndConsumesMediaTypes() {
        DispatcherServletMappingDescription description = conditioned(
                Set.of("/api/things"),
                Set.of(RequestMethod.POST, RequestMethod.GET),
                "{ [/api/things] }",
                "org.example.ThingController#things",
                List.of(media("application/json", false), media("application/cbor", false)),
                List.of(media("text/plain", true)));
        SpringMappingProvider provider =
                new SpringMappingProvider(() -> endpointReturning(description), selfDataFilter);

        // Two methods (sorted GET before POST), produces joined+sorted, consumes negated with "!".
        assertThat(provider.mappings())
                .extracting(MappingDto::method, MappingDto::pattern, MappingDto::produces, MappingDto::consumes)
                .containsExactly(
                        Tuple.tuple("GET", "/api/things", "application/cbor, application/json", "!text/plain"),
                        Tuple.tuple("POST", "/api/things", "application/cbor, application/json", "!text/plain"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static DispatcherServletMappingDescription predicateOnly(String predicate, String handler) {
        DispatcherServletMappingDescription description = mock(DispatcherServletMappingDescription.class);
        when(description.getPredicate()).thenReturn(predicate);
        when(description.getHandler()).thenReturn(handler);
        when(description.getDetails()).thenReturn(null);
        return description;
    }

    private static DispatcherServletMappingDescription conditioned(
            Set<String> patterns,
            Set<RequestMethod> methods,
            String predicate,
            String handler,
            List<MediaTypeExpressionDescription> produces,
            List<MediaTypeExpressionDescription> consumes) {
        RequestMappingConditionsDescription conditions = mock(RequestMappingConditionsDescription.class);
        when(conditions.getPatterns()).thenReturn(patterns);
        when(conditions.getMethods()).thenReturn(methods);
        when(conditions.getProduces()).thenReturn(produces);
        when(conditions.getConsumes()).thenReturn(consumes);
        DispatcherServletMappingDetails details = mock(DispatcherServletMappingDetails.class);
        when(details.getRequestMappingConditions()).thenReturn(conditions);
        DispatcherServletMappingDescription description = mock(DispatcherServletMappingDescription.class);
        when(description.getDetails()).thenReturn(details);
        when(description.getPredicate()).thenReturn(predicate);
        when(description.getHandler()).thenReturn(handler);
        return description;
    }

    private static MediaTypeExpressionDescription media(String mediaType, boolean negated) {
        MediaTypeExpressionDescription expression = mock(MediaTypeExpressionDescription.class);
        when(expression.getMediaType()).thenReturn(mediaType);
        when(expression.isNegated()).thenReturn(negated);
        return expression;
    }

    private static MappingsEndpoint endpointReturning(DispatcherServletMappingDescription... descriptions) {
        ContextMappingsDescriptor context =
                mock(ContextMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(context.getMappings())
                .thenReturn(Map.of("dispatcherServlets", Map.of("dispatcherServlet", List.of(descriptions))));
        ApplicationMappingsDescriptor descriptor =
                mock(ApplicationMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getContexts()).thenReturn(Map.of("application", context));
        MappingsEndpoint endpoint = mock(MappingsEndpoint.class);
        when(endpoint.mappings()).thenReturn(descriptor);
        return endpoint;
    }
}
