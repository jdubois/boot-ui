package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SseStreamsTests {

    @Test
    void coalescesAnyNumberOfTicksIntoOnePendingUpdateUnderBackpressure() {
        AtomicReference<Runnable> signal = new AtomicReference<>();
        AtomicInteger openStreams = new AtomicInteger();
        OutboundSseEvent event = mock(OutboundSseEvent.class);
        AssertSubscriber<OutboundSseEvent> subscriber = SseStreams.updates(sse(event), openStreams, 2, listener -> {
                    signal.set(listener);
                    return () -> {};
                })
                .subscribe()
                .withSubscriber(AssertSubscriber.create(0));

        for (int index = 0; index < 1_000; index++) {
            signal.get().run();
        }
        subscriber.assertHasNotReceivedAnyItem();

        subscriber.request(1).assertItems(event);
        subscriber.request(1).assertItems(event);

        signal.get().run();
        subscriber.assertItems(event, event);
        subscriber.cancel();
        assertThat(openStreams).hasValue(0);
    }

    @Test
    void preservesIndividualDeliveryWhenDemandIsAvailable() {
        AtomicReference<Runnable> signal = new AtomicReference<>();
        OutboundSseEvent event = mock(OutboundSseEvent.class);
        AssertSubscriber<OutboundSseEvent> subscriber = SseStreams.updates(
                        sse(event), new AtomicInteger(), 1, listener -> {
                            signal.set(listener);
                            return () -> {};
                        })
                .subscribe()
                .withSubscriber(AssertSubscriber.create(2));

        signal.get().run();
        signal.get().run();

        subscriber.assertItems(event, event).cancel();
    }

    @Test
    void rejectsStreamsBeyondTheConfiguredMaximumAndReleasesTheSlotOnCancellation() {
        AtomicInteger openStreams = new AtomicInteger();
        AtomicInteger unsubscribes = new AtomicInteger();
        Sse sse = sse(mock(OutboundSseEvent.class));
        SseStreams.ChangeSource source = listener -> unsubscribes::incrementAndGet;

        AssertSubscriber<OutboundSseEvent> first =
                SseStreams.updates(sse, openStreams, 1, source).subscribe().withSubscriber(AssertSubscriber.create());
        AssertSubscriber<OutboundSseEvent> rejected =
                SseStreams.updates(sse, openStreams, 1, source).subscribe().withSubscriber(AssertSubscriber.create());

        rejected.assertCompleted();
        assertThat(openStreams).hasValue(1);
        first.cancel();
        assertThat(openStreams).hasValue(0);
        assertThat(unsubscribes).hasValue(1);
    }

    private static Sse sse(OutboundSseEvent event) {
        Sse sse = mock(Sse.class);
        OutboundSseEvent.Builder builder = mock(OutboundSseEvent.Builder.class, RETURNS_SELF);
        when(sse.newEventBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(event);
        return sse;
    }
}
