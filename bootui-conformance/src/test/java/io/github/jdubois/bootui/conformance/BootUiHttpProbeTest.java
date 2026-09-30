package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BootUiHttpProbeTest {

    @Test
    void waitsForTheBlankLineThatEndsTheEventCarryingTheNeedle() {
        String partial = "event:log\ndata:{\"logger\":\"needle\",\"message\":\"cut here";

        assertThat(BootUiHttpProbe.holdsCompleteEventWith(partial, "needle")).isFalse();
        assertThat(BootUiHttpProbe.holdsCompleteEventWith(partial + "\"}\n\n", "needle"))
                .isTrue();
        assertThat(BootUiHttpProbe.holdsCompleteEventWith(partial + "\"}\r\n\r\n", "needle"))
                .isTrue();
    }

    @Test
    void ignoresEventsThatEndBeforeTheNeedle() {
        assertThat(BootUiHttpProbe.holdsCompleteEventWith("data:{}\n\ndata:{\"logger\":\"needle\"", "needle"))
                .isFalse();
        assertThat(BootUiHttpProbe.holdsCompleteEventWith("data:{}\n\n", "needle"))
                .isFalse();
    }
}
