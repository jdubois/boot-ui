package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code docs/PLAN-v2.md} §5.1: the phase markers of recent requests. */
class RequestPhasesTests {

    @Test
    void tracksEachRequestsPhaseAndWhenItFirstEnteredEach() {
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");

        assertThat(phases.phaseOf("r1")).isEqualTo(RequestPhase.FILTERS);
        phases.mark("r1", RequestPhase.HANDLER);
        RequestPhases.Markers inHandler = phases.markers("r1");
        phases.mark("r1", RequestPhase.RESPONSE);
        phases.mark("r1", RequestPhase.HANDLER);

        RequestPhases.Markers markers = phases.markers("r1");
        assertThat(markers.current()).isEqualTo(RequestPhase.HANDLER);
        assertThat(markers.handlerAt()).as("the first entry is kept").isEqualTo(inHandler.handlerAt());
        assertThat(markers.filtersAt()).isLessThanOrEqualTo(markers.handlerAt());
        assertThat(markers.responseAt()).isGreaterThanOrEqualTo(markers.handlerAt());
    }

    @Test
    void accumulatesAuthenticationTimeAndIgnoresUntrackedRequests() {
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");
        phases.addAuthentication("r1", 300);
        phases.addAuthentication("r1", 200);
        phases.addAuthentication("r1", -5);
        phases.addAuthentication("unknown", 100);
        phases.mark("unknown", RequestPhase.HANDLER);
        phases.mark(null, RequestPhase.HANDLER);

        assertThat(phases.markers("r1").authenticationMicros()).isEqualTo(500);
        assertThat(phases.phaseOf("unknown")).isNull();
        assertThat(phases.markers(null)).isNull();
    }

    @Test
    void recordsWhenARequestEndedOnce() {
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");

        assertThat(phases.markers("r1").endedAt()).as("still running").isNull();
        phases.end("r1");
        Long ended = phases.markers("r1").endedAt();
        phases.end("r1");
        phases.end("unknown");
        phases.end(null);

        assertThat(ended).isGreaterThanOrEqualTo(phases.markers("r1").filtersAt());
        assertThat(phases.markers("r1").endedAt()).as("the first end is kept").isEqualTo(ended);
    }

    @Test
    void forgetsTheOldestRequestsFirst() {
        RequestPhases phases = new RequestPhases(2);
        phases.begin("r1");
        phases.begin("r2");
        phases.begin("r3");

        assertThat(phases.phaseOf("r1")).isNull();
        assertThat(phases.phaseOf("r2")).isEqualTo(RequestPhase.FILTERS);
        assertThat(phases.phaseOf("r3")).isEqualTo(RequestPhase.FILTERS);
    }

    @Test
    void endListenersHearEveryEndEvenOfAForgottenRequestAndAFailingOneChangesNothing() {
        RequestPhases phases = new RequestPhases(1);
        java.util.List<String> heard = new java.util.ArrayList<>();
        phases.addEndListener(id -> {
            throw new IllegalStateException("listener failure");
        });
        java.util.function.Consumer<String> listener = heard::add;
        phases.addEndListener(listener);
        phases.begin("first");
        phases.begin("second");

        phases.end("first");
        phases.end("second");
        phases.end("second");
        phases.end(null);

        assertThat(heard).containsExactly("first", "second", "second");
        assertThat(phases.markers("first")).as("forgotten").isNull();
        assertThat(phases.markers("second").endedAt()).isNotNull();

        phases.removeEndListener(listener);
        phases.end("third");
        assertThat(heard).hasSize(3);
    }
}
