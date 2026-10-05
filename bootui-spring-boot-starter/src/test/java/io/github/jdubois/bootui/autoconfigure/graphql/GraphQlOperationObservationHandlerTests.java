package io.github.jdubois.bootui.autoconfigure.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.execution.DefaultExecutionGraphQlService;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.observation.GraphQlObservationInstrumentation;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;

/**
 * {@code docs/PLAN-v2.md} §5.1: through a real Spring for GraphQL execution, each request records the operation
 * graphql-java parsed, and the operation becomes part of the request's route.
 */
class GraphQlOperationObservationHandlerTests {

    private static final String SCHEMA = """
            type Query { products: [String] }
            type Mutation { addProduct(name: String): String }
            """;

    private final RequestPhases phases = new RequestPhases();

    @Test
    void recordsTheParsedOperationOfEachRequest() {
        ExecutionGraphQlService graphQl = service();

        execute(graphQl, "0123456789abcdef", "query ProductList { products }", null);
        execute(graphQl, "fedcba9876543210", "mutation { addProduct(name: \"a\") }", null);
        execute(graphQl, "00112233aabbccdd", "query First { products } query Second { products }", "Second");

        assertThat(phases.operationOf("0123456789abcdef")).isEqualTo("query ProductList");
        assertThat(phases.operationOf("fedcba9876543210")).isEqualTo("mutation");
        assertThat(phases.operationOf("00112233aabbccdd")).isEqualTo("query Second");
    }

    @Test
    void anOperationIsPartOfTheRouteSoEachOneIsRankedOnItsOwn() {
        RouteLabel query = RouteLabel.of("POST", "/graphql", "/graphql", "query ProductList", null);
        RouteLabel mutation = RouteLabel.of("POST", "/graphql", "/graphql", "mutation AddProduct", null);
        RouteLabel plain = RouteLabel.of("POST", "/graphql", "/graphql", null, null);

        assertThat(query.route()).isEqualTo("/graphql (query ProductList)");
        assertThat(query.id()).isNotEqualTo(mutation.id()).isNotEqualTo(plain.id());
        assertThat(plain.route()).isEqualTo("/graphql");
    }

    private ExecutionGraphQlService service() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new GraphQlOperationObservationHandler(phases));
        GraphQlSource source = GraphQlSource.schemaResourceBuilder()
                .schemaResources(new ByteArrayResource(SCHEMA.getBytes(StandardCharsets.UTF_8)))
                .configureRuntimeWiring(wiring -> wiring.type(
                                "Query", type -> type.dataFetcher("products", environment -> List.of("a")))
                        .type("Mutation", type -> type.dataFetcher("addProduct", environment -> "a")))
                .instrumentation(List.of(new GraphQlObservationInstrumentation(registry)))
                .build();
        return new DefaultExecutionGraphQlService(source);
    }

    private void execute(ExecutionGraphQlService graphQl, String requestId, String document, String operationName) {
        phases.begin(requestId);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
            graphQl.execute(new DefaultExecutionGraphQlRequest(
                            document, operationName, Map.of(), Map.of(), requestId, Locale.ENGLISH))
                    .block(Duration.ofSeconds(10));
        }
    }
}
