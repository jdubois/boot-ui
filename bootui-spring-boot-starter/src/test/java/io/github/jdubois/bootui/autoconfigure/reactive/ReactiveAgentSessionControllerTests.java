package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.dto.CopilotDashboardDto;
import io.github.jdubois.bootui.core.dto.CopilotSessionListDto;
import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import io.github.jdubois.bootui.engine.agent.AgentSessionStore.RefreshEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;

class ReactiveAgentSessionControllerTests {

    @Test
    void slowSubscriberFinishesTheCurrentPairAndSkipsSupersededRefreshes() {
        var source = new SnapshotSource();
        var controller = new ReactiveAgentSessionController(
                () -> source.store, new BootUiExposure(new BootUiProperties()), "test") {};

        StepVerifier.create(controller.stream().map(event -> tuple(event.event(), event.data())), 0)
                .expectSubscription()
                .then(() -> {
                    source.refresh(1);
                    source.refresh(2);
                })
                .thenRequest(3)
                .expectNext(
                        tuple("sessions", sessions(0)),
                        tuple("dashboard", dashboard(0)),
                        tuple("sessions", sessions(2)))
                .then(() -> {
                    source.refresh(3);
                    source.refresh(4);
                })
                .thenRequest(3)
                .expectNext(
                        tuple("dashboard", dashboard(2)),
                        tuple("sessions", sessions(4)),
                        tuple("dashboard", dashboard(4)))
                .thenRequest(2)
                .then(() -> source.refresh(4))
                .expectNext(tuple("sessions", sessions(4)), tuple("dashboard", dashboard(4)))
                .then(() -> source.refresh(5))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(source.listener).isNull();
    }

    @Test
    void demandEndingAtAPairBoundaryDoesNotPinAnUnstartedRefresh() {
        var source = new SnapshotSource();
        var controller = new ReactiveAgentSessionController(
                () -> source.store, new BootUiExposure(new BootUiProperties()), "test") {};
        var events = new ArrayList<Tuple>();
        var subscriber = new BaseSubscriber<Tuple>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(2);
            }

            @Override
            protected void hookOnNext(Tuple event) {
                events.add(event);
            }
        };

        try {
            controller.stream().map(event -> tuple(event.event(), event.data())).subscribe(subscriber);
            assertThat(events).containsExactly(tuple("sessions", sessions(0)), tuple("dashboard", dashboard(0)));

            source.refresh(1);
            source.refresh(2);
            assertThat(events).hasSize(2);

            subscriber.request(2);
            assertThat(events)
                    .containsExactly(
                            tuple("sessions", sessions(0)),
                            tuple("dashboard", dashboard(0)),
                            tuple("sessions", sessions(2)),
                            tuple("dashboard", dashboard(2)));
        } finally {
            subscriber.cancel();
        }
    }

    private static CopilotSessionListDto sessions(int count) {
        return new CopilotSessionListDto(true, null, "synthetic", count, 0, 100, List.of(), List.of());
    }

    private static CopilotDashboardDto dashboard(int count) {
        return new CopilotDashboardDto(
                true,
                null,
                "synthetic",
                count,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                0,
                null,
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static final class SnapshotSource {
        private final AgentSessionStore store = mock(AgentSessionStore.class);
        private Consumer<RefreshEvent> listener;
        private int count;
        private long sequence;

        private SnapshotSource() {
            when(store.listSessions()).thenAnswer(invocation -> sessions(count));
            when(store.dashboard()).thenAnswer(invocation -> dashboard(count));
            when(store.subscribe(any())).thenAnswer(invocation -> {
                listener = invocation.getArgument(0);
                return (Runnable) () -> listener = null;
            });
        }

        private void refresh(int count) {
            this.count = count;
            listener.accept(new RefreshEvent(++sequence, count));
        }
    }
}
