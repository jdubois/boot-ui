package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the cross-anchor trace-uniqueness guard shared by the Live Activity feed and profiles. */
class TraceCorrelationIndexTests {

    @Test
    void attachesATraceCarriedByExactlyOneRequestWhateverTheChildTimestamp() {
        TraceCorrelationIndex index = TraceCorrelationIndex.of(List.of(exchange("r1", "trace-a", 1_000L)));

        assertThat(index.parentRequestId("trace-a")).isEqualTo("r1");
        assertThat(index.match("trace-a", 99_000L).status()).isEqualTo(TraceCorrelationIndex.Status.ATTACHED);
        assertThat(index.match("trace-a", 99_000L).anchor().id()).isEqualTo("r1");
    }

    @Test
    void ignoresBlankAndUnknownTraceIds() {
        TraceCorrelationIndex index = TraceCorrelationIndex.of(List.of(exchange("r1", " ", 1_000L)));

        assertThat(index.parentRequestId(" ")).isNull();
        assertThat(index.parentRequestId(null)).isNull();
        assertThat(index.match("trace-z", 1_000L).status()).isEqualTo(TraceCorrelationIndex.Status.UNCLAIMED);
    }

    @Test
    void attachesNothingToATraceCarriedByTwoAnchorsOfAnyType() {
        ProfileAnchor request = ProfileAnchor.request(exchange("r1", "trace-a", 1_000L), null);
        ProfileAnchor execution = bounded("exec-1", "trace-a", 1_020L, 1_040L);
        TraceCorrelationIndex index = TraceCorrelationIndex.ofAnchors(List.of(request, execution));

        TraceCorrelationIndex.Match match = index.match("trace-a", 1_030L);

        assertThat(match.status()).isEqualTo(TraceCorrelationIndex.Status.AMBIGUOUS);
        assertThat(match.carriedBy(request)).isTrue();
        assertThat(match.carriedBy(execution)).isTrue();
        assertThat(index.parentRequestId("trace-a")).isNull();
        assertThat(index.isShared("trace-a")).isTrue();
    }

    @Test
    void attachesByTraceOnlyInsideABoundedAnchorWindow() {
        ProfileAnchor execution = bounded("exec-1", "trace-a", 1_000L, 1_100L);
        TraceCorrelationIndex index = TraceCorrelationIndex.ofAnchors(List.of(execution));

        assertThat(index.match("trace-a", 1_140L).status()).isEqualTo(TraceCorrelationIndex.Status.ATTACHED);
        assertThat(index.match("trace-a", 1_151L).status()).isEqualTo(TraceCorrelationIndex.Status.OUTSIDE_WINDOW);
        assertThat(index.match("trace-a", 949L).status()).isEqualTo(TraceCorrelationIndex.Status.OUTSIDE_WINDOW);
    }

    private static ProfileAnchor bounded(String id, String traceId, long start, long end) {
        return new ProfileAnchor(
                ProfileAnchor.Type.REQUEST, id, traceId, start, end, true, null, start, end, null, null, null);
    }

    private static HttpExchangeDto exchange(String id, String traceId, long epochMillis) {
        return new HttpExchangeDto(
                id,
                Instant.ofEpochMilli(epochMillis),
                "GET",
                "/a",
                null,
                "/a",
                200,
                "2xx",
                10L,
                null,
                null,
                null,
                null,
                traceId,
                List.of(),
                List.of());
    }
}
