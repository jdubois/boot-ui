package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ThrownExceptionModel;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RAPI-ERR-009 deduplicates its texts, so a text standing for several endpoints must not keep any one location. */
class DeclaredExceptionLocationTests {

    private static ThrownExceptionModel thrown(String controller, String method) {
        return new ThrownExceptionModel(
                controller, method, "com.example.OrderMissing", "OrderMissing", List.of("java.lang.RuntimeException"));
    }

    private static AdvisorViolationLocationDto location(String className, int line) {
        return new AdvisorViolationLocationDto(className, "find", "METHOD", "Orders.java", line, null);
    }

    @Test
    void aDeduplicatedTextKeepsALocationOnlyWhenEveryOccurrenceAgrees() {
        ThrownExceptionModel overloadOne = thrown("Orders", "find");
        ThrownExceptionModel overloadTwo = thrown("Orders", "find");
        ThrownExceptionModel sameElementOne = thrown("Invoices", "find");
        ThrownExceptionModel sameElementTwo = thrown("Invoices", "find");
        ThrownExceptionModel single = thrown("Payments", "find");
        IdentityHashMap<Object, AdvisorViolationLocationDto> locations = new IdentityHashMap<>();
        locations.put(overloadOne, location("com.example.Orders", 10));
        locations.put(overloadTwo, location("com.example.Orders", 20));
        locations.put(sameElementOne, location("com.example.Invoices", 7));
        locations.put(sameElementTwo, location("com.example.Invoices", 7));
        locations.put(single, location("com.example.Payments", 3));
        RestApiContext context = new RestApiContext(
                List.of("com.example"),
                List.of(),
                List.of(),
                List.of(new RestApiModel.ExceptionHandlerModel(
                        "com.example.Advice",
                        "other",
                        "org.springframework.http.ProblemDetail",
                        true,
                        false,
                        false,
                        false,
                        "",
                        false,
                        false,
                        true,
                        List.of("com.example.Unrelated"),
                        List.of(),
                        RestApiModel.Framework.SPRING)),
                false,
                false,
                true,
                List.of(),
                List.of(overloadOne, overloadTwo, sameElementOne, sameElementTwo, single),
                RestApiModel.Framework.SPRING,
                new RestApiEvaluationEvidence(),
                new AdvisorViolationCollector(100),
                RestApiLocations.of(locations));

        RestApiRuleResultDto result = new DeclaredExceptionsHaveHandlersRule().evaluate(context);

        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .containsExactly(
                        "Orders#find declares OrderMissing, for which no handler declaration was found in the"
                                + " imported model",
                        "Invoices#find declares OrderMissing, for which no handler declaration was found in the"
                                + " imported model",
                        "Payments#find declares OrderMissing, for which no handler declaration was found in the"
                                + " imported model");
        assertThat(result.sampleLocations())
                .containsExactly(null, location("com.example.Invoices", 7), location("com.example.Payments", 3));
    }
}
