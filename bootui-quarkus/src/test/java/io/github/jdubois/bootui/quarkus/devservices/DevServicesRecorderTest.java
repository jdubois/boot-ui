package io.github.jdubois.bootui.quarkus.devservices;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DevServicesRecorderTest {

    private final DevServicesRecorder recorder = new DevServicesRecorder();

    @AfterEach
    void clearSnapshot() {
        recorder.capture(List.of());
    }

    @Test
    void capturePublishesTheStartedDevServices() {
        RawDevService postgres = new RawDevService("default", "postgres:17", "abc123", Map.of());

        recorder.capture(List.of(postgres));

        assertThat(CapturedDevServices.current())
                .hasValueSatisfying(snapshot -> assertThat(snapshot.services()).containsExactly(postgres));
    }

    @Test
    void anEmptyCaptureClearsTheSnapshotOfAPreviousStart() {
        recorder.capture(List.of(new RawDevService("default", "postgres:17", "abc123", Map.of())));

        recorder.capture(List.of());

        assertThat(CapturedDevServices.current()).isEmpty();
    }
}
