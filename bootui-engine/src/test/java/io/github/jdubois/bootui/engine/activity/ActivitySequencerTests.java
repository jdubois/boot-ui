package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import org.junit.jupiter.api.Test;

class ActivitySequencerTests {

    @Test
    void stampsMonotonicSequenceStartingAtOne() {
        ActivitySequencer sequencer = new ActivitySequencer("app-1");
        ActivityEntryDto entry = entry("1", "REQUEST", 1, "OK", "a");

        StoredActivityEntry first = sequencer.stamp(entry);
        StoredActivityEntry second = sequencer.stamp(entry);

        assertThat(first.instanceId()).isEqualTo("app-1");
        assertThat(first.seq()).isEqualTo(1L);
        assertThat(second.seq()).isEqualTo(2L);
    }

    @Test
    void exposesItsInstanceId() {
        ActivitySequencer sequencer = new ActivitySequencer("app-42");
        assertThat(sequencer.instanceId()).isEqualTo("app-42");
    }

    @Test
    void rejectsNullInstanceId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ActivitySequencer(null))
                .isInstanceOf(NullPointerException.class);
    }
}
