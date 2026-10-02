package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LifecyclePayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

class ControlMarkerPublisherTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private final GenericApplicationContext context = new GenericApplicationContext();
    private final ControlMarkerPublisher publisher = new ControlMarkerPublisher(journal, context);

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void marksAvailabilityChangesAfterTheRunIsReadyAndItsShutdown() throws Exception {
        publisher.onApplicationEvent(new AvailabilityChangeEvent<>(context, LivenessState.CORRECT));
        publisher.onApplicationEvent(
                new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        publisher.onApplicationEvent(new AvailabilityChangeEvent<>(context, ReadinessState.ACCEPTING_TRAFFIC));
        publisher.onApplicationEvent(new AvailabilityChangeEvent<>(context, ReadinessState.REFUSING_TRAFFIC));
        publisher.onApplicationEvent(new AvailabilityChangeEvent<>(context, ReadinessState.REFUSING_TRAFFIC));
        publisher.onApplicationEvent(new AvailabilityChangeEvent<>(context, LivenessState.BROKEN));
        publisher.onApplicationEvent(new ContextClosedEvent(context));
        publisher.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));

        assertThat(markers())
                .containsExactlyInAnyOrder(
                        new LifecyclePayload(LifecyclePayload.AVAILABILITY, "Readiness REFUSING_TRAFFIC", null),
                        new LifecyclePayload(LifecyclePayload.AVAILABILITY, "Liveness BROKEN", null),
                        new LifecyclePayload(LifecyclePayload.SHUTDOWN, null, null));
    }

    private List<Object> markers() throws InterruptedException {
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        return journal.entries().stream()
                .map(entry -> (Object) entry.event().payload())
                .toList();
    }
}
