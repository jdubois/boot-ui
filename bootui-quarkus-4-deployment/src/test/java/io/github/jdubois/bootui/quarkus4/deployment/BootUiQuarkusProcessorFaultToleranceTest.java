package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.quarkus4.faulttolerance.RawFaultTolerancePolicy;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BootUiQuarkusProcessor#scanFaultTolerancePolicies(org.jboss.jandex.IndexView)}, the
 * build-time Jandex scan behind the Fault Tolerance panel on Quarkus.
 *
 * <p>The fixtures below carry real MicroProfile / SmallRye Fault Tolerance annotations and are indexed with
 * a real {@link Indexer}, so the assertions exercise the exact annotation values the processor reads from
 * bytecode rather than hand-built Jandex objects. That matters for value rendering in particular: Jandex's
 * {@code AnnotationValue#toString()} renders {@code member = value} and quotes strings, so a test over
 * hand-made records would not catch a policy reaching the panel as
 * {@code requestVolumeThreshold = requestVolumeThreshold = 4}.</p>
 */
class BootUiQuarkusProcessorFaultToleranceTest {

    @Test
    void namesACircuitBreakerByItsCircuitBreakerNameAndKeepsTheGuardedMethodAsTheTarget() throws IOException {
        RawFaultTolerancePolicy breaker = policy(index(), "CIRCUIT_BREAKER", "inventory-service");

        assertThat(breaker.annotationName()).isEqualTo("CircuitBreaker");
        assertThat(breaker.circuitBreakerName()).isEqualTo("inventory-service");
        assertThat(breaker.methodName()).isEqualTo("reserve");
        assertThat(breaker.target()).isEqualTo(InventoryClient.class.getName() + "#reserve");
    }

    @Test
    void namesAnAnonymousCircuitBreakerAfterTheMethodItGuards() throws IOException {
        RawFaultTolerancePolicy breaker = policy(index(), "CIRCUIT_BREAKER", "PricingClient#quote");

        assertThat(breaker.circuitBreakerName()).isEmpty();
        assertThat(breaker.target()).isEqualTo(PricingClient.class.getName() + "#quote");
    }

    @Test
    void rendersConfiguredValuesWithoutTheJandexMemberPrefixOrQuotes() throws IOException {
        Index index = index();

        assertThat(settings(policy(index, "CIRCUIT_BREAKER", "inventory-service")))
                .contains(
                        tuple("requestVolumeThreshold", "4", "CONFIGURED"),
                        tuple("failureRatio", "0.75", "CONFIGURED"),
                        tuple("delay", "2", "CONFIGURED"),
                        tuple("delayUnit", "SECONDS", "CONFIGURED"));
        assertThat(settings(policy(index, "RETRY", "InventoryClient#reserve")))
                .contains(tuple("maxRetries", "2", "CONFIGURED"));
        assertThat(settings(policy(index, "FALLBACK", "InventoryClient#reserve")))
                .containsExactly(tuple("fallbackMethod", "recover", "CONFIGURED"));
        assertThat(settings(policy(index, "BULKHEAD", "PricingClient#quote")))
                .contains(tuple("value", "4", "CONFIGURED"), tuple("waitingTaskQueue", "8", "CONFIGURED"));
    }

    @Test
    void reportsUnwrittenMembersWithTheSpecificationDefault() throws IOException {
        assertThat(settings(policy(index(), "TIME_LIMITER", "PricingClient#quote")))
                .containsExactly(tuple("value", "1000", "DEFAULT"), tuple("unit", "MILLIS", "DEFAULT"));
    }

    @Test
    void capturesEveryAnnotationOnAGuardedMethodAsItsOwnPolicy() throws IOException {
        List<RawFaultTolerancePolicy> policies = BootUiQuarkusProcessor.scanFaultTolerancePolicies(index());

        assertThat(policies)
                .extracting(RawFaultTolerancePolicy::type)
                .containsExactlyInAnyOrder(
                        "CIRCUIT_BREAKER", "CIRCUIT_BREAKER", "RETRY", "BULKHEAD", "TIME_LIMITER", "FALLBACK");
    }

    private static Index index() throws IOException {
        Indexer indexer = new Indexer();
        indexer.indexClass(InventoryClient.class);
        indexer.indexClass(PricingClient.class);
        return indexer.complete();
    }

    private static RawFaultTolerancePolicy policy(Index index, String type, String name) {
        return BootUiQuarkusProcessor.scanFaultTolerancePolicies(index).stream()
                .filter(candidate ->
                        candidate.type().equals(type) && candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " policy named " + name + " in "
                        + BootUiQuarkusProcessor.scanFaultTolerancePolicies(index)));
    }

    private static List<Tuple> settings(RawFaultTolerancePolicy policy) {
        return policy.settings().stream()
                .map(setting -> tuple(setting.name(), setting.value(), setting.provenance()))
                .toList();
    }

    static class InventoryClient {

        @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.75, delay = 2, delayUnit = ChronoUnit.SECONDS)
        @CircuitBreakerName("inventory-service")
        @Retry(maxRetries = 2)
        @Fallback(fallbackMethod = "recover")
        String reserve() {
            return "reserved";
        }

        String recover() {
            return "unavailable";
        }
    }

    static class PricingClient {

        @CircuitBreaker
        @Bulkhead(value = 4, waitingTaskQueue = 8)
        @Timeout
        String quote() {
            return "quote";
        }
    }
}
