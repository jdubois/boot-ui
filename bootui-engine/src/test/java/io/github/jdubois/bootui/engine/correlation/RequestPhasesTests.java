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
    void forgetsTheOldestRequestsFirst() {
        RequestPhases phases = new RequestPhases(2);
        phases.begin("r1");
        phases.begin("r2");
        phases.begin("r3");

        assertThat(phases.phaseOf("r1")).isNull();
        assertThat(phases.phaseOf("r2")).isEqualTo(RequestPhase.FILTERS);
        assertThat(phases.phaseOf("r3")).isEqualTo(RequestPhase.FILTERS);
    }
}
