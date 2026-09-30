package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation.Scope;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CorrelationIdentityTests {

    @AfterEach
    void clearThread() {
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    @Test
    void withersCopyTheContextAndChangeOneAspect() {
        CorrelationContext request = CorrelationContext.forRequest("r1")
                .withTrace("t1", "s1")
                .withRoute("/api/orders/{id}", "OrderController#get")
                .withTransactionId("tx1")
                .withDataSource("primary");

        assertThat(request)
                .isEqualTo(new CorrelationContext(
                        "r1", null, "t1", "s1", "/api/orders/{id}", "OrderController#get", "tx1", "primary"));
        assertThat(request.withTransactionId(null).transactionId()).isNull();
        assertThat(request.transactionId()).isEqualTo("tx1");
        assertThat(CorrelationContext.forExecution("e1").executionId()).isEqualTo("e1");
        assertThat(CorrelationContext.forExecution("e1").requestId()).isNull();
    }

    @Test
    void onlyTheContextWithoutAnyIdentityIsEmpty() {
        assertThat(CorrelationContext.NONE.isEmpty()).isTrue();
        assertThat(CorrelationContext.NONE.withDataSource("primary").isEmpty()).isFalse();
        assertThat(CorrelationContext.forRequest("r1").isEmpty()).isFalse();
    }

    @Test
    void requestIdsAreSixteenHexCharactersAndDistinct() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = RequestIds.next();
            assertThat(id).matches("[0-9a-f]{16}");
            ids.add(id);
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void runsHaveDistinctIdsAndIncreasingOrdinals() {
        RunIdentity first = RunIdentity.start(() -> 1_000L);
        RunIdentity second = RunIdentity.start(() -> 2_000L);

        assertThat(first.id()).matches("[0-9a-f]{8}");
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.ordinal()).isEqualTo(first.ordinal() + 1);
        assertThat(first.startedAtEpochMillis()).isEqualTo(1_000L);
    }

    @Test
    void providerReturnsTheScopeContextAsIs() {
        ScopedCorrelationContextProvider provider = new ScopedCorrelationContextProvider(() -> "from-tracer");
        CorrelationContext traced = CorrelationContext.forRequest("r1").withTrace("t1", "s1");

        try (Scope ignored = BootUiCorrelation.open(traced)) {
            assertThat(provider.current()).isEqualTo(traced);
        }
    }

    @Test
    void providerFillsInTheTraceIdFromTheTracerWhenTheScopeHasNone() {
        ScopedCorrelationContextProvider provider = new ScopedCorrelationContextProvider(() -> "t2");

        try (Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            assertThat(provider.current())
                    .isEqualTo(CorrelationContext.forRequest("r1").withTrace("t2", null));
        }
        assertThat(provider.current()).isEqualTo(CorrelationContext.NONE.withTrace("t2", null));
    }

    @Test
    void providerIgnoresBlankMissingAndFailingTracers() {
        TraceIdProvider failing = () -> {
            throw new IllegalStateException("tracer down");
        };

        assertThat(new ScopedCorrelationContextProvider(null).current()).isSameAs(CorrelationContext.NONE);
        assertThat(new ScopedCorrelationContextProvider(() -> " ").current()).isSameAs(CorrelationContext.NONE);
        assertThat(new ScopedCorrelationContextProvider(() -> null).current()).isSameAs(CorrelationContext.NONE);
        assertThat(new ScopedCorrelationContextProvider(failing).current()).isSameAs(CorrelationContext.NONE);
    }
}
