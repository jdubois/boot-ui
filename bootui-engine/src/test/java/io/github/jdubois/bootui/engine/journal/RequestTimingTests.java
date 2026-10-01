package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import org.junit.jupiter.api.Test;

class RequestTimingTests {

    @Test
    void phaseMarkersBecomeOffsetsFromTheRequestsFirstMarkerInNanoseconds() {
        RequestTiming timing =
                RequestTiming.of(42, new RequestPhases.Markers(1_000L, 1_500L, 4_000L, RequestPhase.RESPONSE, 200));

        assertThat(timing).isEqualTo(new RequestTiming(42, 200_000, 500_000, 3_000_000));
        assertThat(timing.phased()).isTrue();
    }

    @Test
    void aPhaseNeverEnteredOrUnknownMarkersLeaveOnlyTheStart() {
        assertThat(RequestTiming.of(42, new RequestPhases.Markers(1_000L, 1_500L, null, RequestPhase.HANDLER, 0)))
                .isEqualTo(new RequestTiming(42, 0, 500_000, -1));
        assertThat(RequestTiming.of(42, null)).isEqualTo(RequestTiming.startedAt(42));
        assertThat(RequestTiming.startedAt(42).phased()).isFalse();
    }
}
