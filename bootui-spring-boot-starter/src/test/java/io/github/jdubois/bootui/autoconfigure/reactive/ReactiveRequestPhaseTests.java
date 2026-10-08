package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.activity.AuthenticationPhaseObservationHandler;
import io.github.jdubois.bootui.autoconfigure.graphql.GraphQlOperationObservationHandler;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.execution.DefaultExecutionGraphQlService;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.observation.GraphQlObservationInstrumentation;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AuthenticationObservationContext;
import org.springframework.security.authentication.ObservationReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.WebFilterChain;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code docs/PLAN-v2.md} §5.1: a request that runs through {@link ReactiveRequestCorrelationFilter} has its marker
 * timeline begun and ended by that filter, so the shared GraphQL operation and Spring Security authentication
 * observation handlers record into it, and what they recorded reaches the journal. WebFlux marks no handler or
 * response phase, so those stay unknown rather than guessed.
 */
class ReactiveRequestPhaseTests {

    private static final String SCHEMA = """
            type Query { products: [String] }
            """;

    private final RequestPhases phases = new RequestPhases();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final ReactiveRequestCorrelationFilter filter =
            new ReactiveRequestCorrelationFilter(new BootUiProperties());
    private final List<RuntimeEvent> published = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void installHandlers() {
        filter.setRuntimeEventSink(published::add, 1_000, phases);
        observations.observationConfig().observationHandler(new GraphQlOperationObservationHandler(phases));
        observations.observationConfig().observationHandler(new AuthenticationPhaseObservationHandler(phases));
        BootUiCorrelationThreadLocalAccessor.register();
        reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
    }

    @AfterEach
    void resetPropagation() {
        reactor.core.publisher.Hooks.disableAutomaticContextPropagation();
        ContextRegistry.getInstance().removeThreadLocalAccessor(ReactiveRequestCorrelationFilter.CONTEXT_KEY);
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    @Test
    void namesTheGraphQlOperationAndTheAuthenticationTimeOfARequestThatRanThroughTheFilter() {
        ExecutionGraphQlService graphQl = graphQlService();
        ReactiveAuthenticationManager authentication = authenticationManager();
        MockServerWebExchange exchange = exchange("/graphql");
        WebFilterChain chain = ignored -> authentication
                .authenticate(new UsernamePasswordAuthenticationToken("alice", "secret"))
                .then(Mono.defer(() -> execute(graphQl, "query ProductList { products }")))
                .then();

        filter.filter(exchange, chain).block(Duration.ofSeconds(10));

        String requestId =
                ReactiveRequestCorrelationFilter.correlation(exchange).requestId();
        HttpPayload payload = (HttpPayload) publishedEvent(1).payload();
        assertThat(payload.operation())
                .as("the operation graphql-java parsed, which makes this request a route of its own")
                .isEqualTo("query ProductList");
        assertThat(payload.timing().authenticationNanos())
                .as("the time Spring Security observed while authenticating it")
                .isPositive();
        assertThat(payload.timing().phased())
                .as("WebFlux marks no handler or response phase")
                .isFalse();
        assertThat(payload.timing().handlerOffsetNanos()).isEqualTo(-1);
        assertThat(payload.timing().responseOffsetNanos()).isEqualTo(-1);
        assertThat(phases.phaseOf(requestId))
                .as("no phase is reported for a request whose adapter marks none")
                .isNull();
        assertThat(phases.markers(requestId).endedAt())
                .as("the filter ends the timeline, so work handed over knows the request is over")
                .isNotNull();
    }

    @Test
    void endsTheTimelineOfACancelledRequest() {
        MockServerWebExchange exchange = exchange("/api/orders");
        Disposable subscription =
                filter.filter(exchange, ignored -> Mono.never()).subscribe();
        String requestId =
                ReactiveRequestCorrelationFilter.correlation(exchange).requestId();
        assertThat(phases.markers(requestId).endedAt()).isNull();

        subscription.dispose();

        assertThat(phases.markers(requestId).endedAt()).isNotNull();
    }

    @Test
    void keepsWhatWasRecordedWhenTheRequestsChainIsSubscribedAgain() {
        ExecutionGraphQlService graphQl = graphQlService();
        MockServerWebExchange exchange = exchange("/graphql");
        AtomicInteger attempts = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            if (attempts.incrementAndGet() == 1) {
                return execute(graphQl, "query ProductList { products }")
                        .then(Mono.error(new IllegalStateException("retry me")));
            }
            return Mono.empty();
        };
        HttpHandler decorated = filter.apply(
                (request, response) -> filter.filter(exchange, chain).retry(1));

        decorated.handle(exchange.getRequest(), exchange.getResponse()).block(Duration.ofSeconds(10));
        // The failed attempt is published once the response commits, as WebFlux commits it after the handler.
        exchange.getResponse().setComplete().block(Duration.ofSeconds(10));

        assertThat(attempts).hasValue(2);
        assertThat(published).hasSize(2);
        assertThat(published)
                .extracting(RuntimeEvent::requestId)
                .as("both attempts belong to the same physical request")
                .containsOnly(
                        ReactiveRequestCorrelationFilter.correlation(exchange).requestId());
        assertThat(((HttpPayload) published.get(1).payload()).operation())
                .as("the operation its first attempt recorded is not lost when the chain is subscribed again")
                .isEqualTo("query ProductList");
    }

    @Test
    void publishesTheRequestEvenWhenItsChainFails() {
        MockServerWebExchange exchange = exchange("/api/orders");

        assertThatThrownBy(() -> filter.filter(exchange, ignored -> Mono.error(new IllegalStateException("boom")))
                        .block(Duration.ofSeconds(10)))
                .hasMessage("boom");
        String requestId =
                ReactiveRequestCorrelationFilter.correlation(exchange).requestId();
        assertThat(phases.markers(requestId).endedAt()).isNotNull();
        assertThat(published)
                .as("a failure is published once WebFlux's exception handlers rendered it")
                .isEmpty();

        rendered(exchange, HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(published).hasSize(1);
        assertThat(((HttpPayload) published.get(0).payload()).status()).isEqualTo(500);
    }

    @Test
    void endsAndPublishesARequestWhoseChainFailsWhileItAssembles() {
        MockServerWebExchange exchange = exchange("/api/orders");
        WebFilterChain chain = ignored -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.filter(exchange, chain).block(Duration.ofSeconds(10)))
                .hasMessage("boom");
        rendered(exchange, HttpStatus.BAD_REQUEST);

        String requestId =
                ReactiveRequestCorrelationFilter.correlation(exchange).requestId();
        assertThat(((HttpPayload) publishedEvent(1).payload()).status())
                .as("the status the application's exception handler rendered")
                .isEqualTo(400);
        assertThat(phases.markers(requestId).endedAt())
                .as("a chain that throws as it assembles still ends the request's timeline")
                .isNotNull();
    }

    @Test
    void recordsAnAuthenticationObservedWhileTheChainIsStillBeingAssembled() {
        MockServerWebExchange exchange = exchange("/api/orders");
        WebFilterChain chain = ignored -> {
            // A downstream filter that authenticates as it assembles its part of the chain, before the request is
            // subscribed: its observation already belongs to the request.
            Observation.createNotStarted(
                            "spring.security.authentications", AuthenticationObservationContext::new, observations)
                    .observe(ReactiveRequestPhaseTests::sleep);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block(Duration.ofSeconds(10));

        HttpPayload payload = (HttpPayload) publishedEvent(1).payload();
        assertThat(payload.timing().authenticationNanos())
                .as("the authentication its chain observed while assembling")
                .isPositive();
    }

    /** Renders a failure as WebFlux's exception handlers do once the filters unwound: a status, then the commit. */
    private static void rendered(MockServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().setComplete().block(Duration.ofSeconds(10));
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The {@code count}th published event, waiting for it: a request's event is published from the terminal signal's
     * finally, which Reactor runs after the request's own completion, on whichever thread terminated it.
     */
    private RuntimeEvent publishedEvent(int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (published.size() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(published).hasSizeGreaterThanOrEqualTo(count);
        return published.get(count - 1);
    }

    private Mono<Void> execute(ExecutionGraphQlService graphQl, String document) {
        return graphQl.execute(new DefaultExecutionGraphQlRequest(
                        document,
                        null,
                        Map.of(),
                        Map.of(),
                        BootUiCorrelation.current().requestId(),
                        Locale.ENGLISH))
                .then();
    }

    private ExecutionGraphQlService graphQlService() {
        GraphQlSource source = GraphQlSource.schemaResourceBuilder()
                .schemaResources(new ByteArrayResource(SCHEMA.getBytes(StandardCharsets.UTF_8)))
                .configureRuntimeWiring(wiring ->
                        wiring.type("Query", type -> type.dataFetcher("products", environment -> List.of("a"))))
                .instrumentation(List.of(new GraphQlObservationInstrumentation(observations)))
                .build();
        return new DefaultExecutionGraphQlService(source);
    }

    /** Spring Security's own observed manager, which authenticates on another thread, as a real one may. */
    private ReactiveAuthenticationManager authenticationManager() {
        ReactiveAuthenticationManager delegate = authentication -> Mono.fromCallable(() -> {
                    Thread.sleep(5);
                    return (Authentication) UsernamePasswordAuthenticationToken.authenticated(
                            authentication.getPrincipal(), authentication.getCredentials(), List.of());
                })
                .subscribeOn(Schedulers.boundedElastic());
        return new ObservationReactiveAuthenticationManager(observations, delegate);
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
    }
}
