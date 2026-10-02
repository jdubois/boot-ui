package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.CorrelationContext;
import org.junit.jupiter.api.Test;

class AiCallOwnersTests {

    @Test
    void aCallBelongsToTheOneRequestWithItsTraceWhoseTimeSpanContainsItsStart() {
        AiCallOwners owners = new AiCallOwners();
        owners.learn(http("r1", "trace-1", 1_000, 50_000_000));
        owners.learn(http("r2", "trace-1", 2_000, 50_000_000));

        assertThat(owners.ownerOf(ai("trace-1", 1_010))).isEqualTo("r1");
        assertThat(owners.ownerOf(ai("trace-1", 2_049))).isEqualTo("r2");
        assertThat(owners.ownerOf(ai("trace-1", 1_500))).as("between the two").isNull();
        assertThat(owners.ownerOf(ai("trace-2", 1_010))).as("another trace").isNull();
        assertThat(owners.ownerOf(ai("trace-1", 1_000 - AiCallOwners.TOLERANCE_MILLIS)))
                .as("clocks round independently")
                .isEqualTo("r1");
        assertThat(owners.ownerOf(ai("trace-1", 1_000 - AiCallOwners.TOLERANCE_MILLIS - 1)))
                .isNull();
    }

    @Test
    void overlappingRequestsSharingTheTraceNameNoOwner() {
        AiCallOwners owners = new AiCallOwners();
        owners.learn(http("r1", "trace-1", 1_000, 50_000_000));
        owners.learn(http("r2", "trace-1", 1_020, 50_000_000));

        assertThat(owners.ownerOf(ai("trace-1", 1_030))).isNull();
        assertThat(owners.unresolved(ai("trace-1", 1_030)))
                .as("already ambiguous, so no later request settles it")
                .isFalse();
        assertThat(owners.ownerOf(ai("trace-1", 1_010))).isEqualTo("r1");
    }

    @Test
    void aCallNoLearnedRequestContainsIsUnresolved() {
        AiCallOwners owners = new AiCallOwners();
        RuntimeEvent call = ai("trace-1", 1_010);

        assertThat(owners.unresolved(call)).isTrue();
        owners.learn(http("r0", "trace-1", 500, 10_000_000));
        assertThat(owners.unresolved(call)).as("r0 ended before it started").isTrue();
        owners.learn(http("r1", "trace-1", 1_000, 50_000_000));
        assertThat(owners.unresolved(call)).isFalse();
        assertThat(owners.unresolved(http("r2", "trace-1", 1_000, 1)))
                .as("not an AI call")
                .isFalse();

        owners.clear();
        assertThat(owners.unresolved(call)).isTrue();
    }

    @Test
    void aRequestOfUnknownDurationSpansOnlyItsStart() {
        AiCallOwners owners = new AiCallOwners();
        owners.learn(http("r1", "trace-1", 1_000, -1));

        assertThat(owners.ownerOf(ai("trace-1", 1_001))).isEqualTo("r1");
        assertThat(owners.ownerOf(ai("trace-1", 1_010)))
                .as("no evidence that it was still running")
                .isNull();
    }

    @Test
    void aCallCarryingItsOwnRequestOrExecutionIsNotLinkedByTrace() {
        AiCallOwners owners = new AiCallOwners();
        owners.learn(http("r1", "trace-1", 1_000, 50_000_000));
        RuntimeEvent ofRequest = RuntimeEvent.of(
                JournalSource.AI,
                1_010,
                1,
                CorrelationContext.forRequest("r9").withTrace("trace-1", null),
                null,
                null,
                false,
                payload());
        RuntimeEvent ofExecution = RuntimeEvent.of(
                JournalSource.AI,
                1_010,
                1,
                CorrelationContext.forExecution("e1").withTrace("trace-1", null),
                null,
                null,
                false,
                payload());

        assertThat(owners.ownerOf(ofRequest)).isEqualTo("r9");
        assertThat(AiCallOwners.linksByTrace(ofExecution)).isFalse();
        assertThat(owners.ownerOf(ofExecution)).isNull();
    }

    @Test
    void aBoundedOneFailsClosedOnATraceItForgotAndOnACrowdedTrace() {
        AiCallOwners owners = AiCallOwners.bounded();
        owners.learn(http("first", "trace-first", 1_000, 50_000_000));
        for (int i = 0; i < AiCallOwners.MAX_TRACES; i++) {
            owners.learn(http("r" + i, "trace-" + i, 1_000, 50_000_000));
        }
        assertThat(owners.traces()).isEqualTo(AiCallOwners.MAX_TRACES);
        assertThat(owners.ownerOf(ai("trace-first", 1_010))).isNull();
        assertThat(owners.unresolved(ai("trace-first", 1_010)))
                .as("the forgotten request may own it, so no later request may claim it")
                .isFalse();
        owners.learn(http("later", "trace-first", 1_005, 50_000_000));
        assertThat(owners.ownerOf(ai("trace-first", 1_010))).isNull();

        for (int i = 0; i <= AiCallOwners.MAX_REQUESTS_PER_TRACE; i++) {
            owners.learn(http("crowd" + i, "trace-crowd", 1_000 + i * 1_000L, 1_000_000));
        }
        assertThat(owners.ownerOf(ai("trace-crowd", 1_000)))
                .as("too many requests share it")
                .isNull();
        assertThat(owners.unresolved(ai("trace-crowd", 1_000))).isFalse();

        AiCallOwners unbounded = new AiCallOwners();
        for (int i = 0; i <= AiCallOwners.MAX_REQUESTS_PER_TRACE; i++) {
            unbounded.learn(http("crowd" + i, "trace-crowd", 1_000 + i * 1_000L, 1_000_000));
        }
        assertThat(unbounded.ownerOf(ai("trace-crowd", 1_000))).isEqualTo("crowd0");
    }

    private static RuntimeEvent http(String requestId, String traceId, long epochMillis, long nanos) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                epochMillis,
                nanos,
                CorrelationContext.forRequest(requestId).withTrace(traceId, null),
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/a", "/a", null, 200));
    }

    private static RuntimeEvent ai(String traceId, long epochMillis) {
        return RuntimeEvent.of(
                JournalSource.AI,
                epochMillis,
                1_000_000,
                CorrelationContext.NONE.withTrace(traceId, null),
                null,
                null,
                false,
                payload());
    }

    private static AiPayload payload() {
        return new AiPayload("chat", "openai", "gpt-4o", 1L, 1L, "stop", false);
    }
}
