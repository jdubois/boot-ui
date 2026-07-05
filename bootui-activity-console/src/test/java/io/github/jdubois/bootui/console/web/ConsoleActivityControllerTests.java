package io.github.jdubois.bootui.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.console.activity.ConsoleActivityChangeStream;
import io.github.jdubois.bootui.console.activity.ConsoleActivityProperties;
import io.github.jdubois.bootui.console.activity.ReactiveActivityStore;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Mirrors the host adapters' plain-instantiation, no-Spring-context controller test style (see e.g.
 * {@code ActivityForwardingControllerTests}): this controller is a thin adapter over the already
 * fully unit-tested {@link io.github.jdubois.bootui.console.activity.ConsoleActivityReportAssembler}
 * and {@link io.github.jdubois.bootui.console.activity.ConsoleActivityProfileAssembler}, so these
 * tests pin only the controller's own wiring (query params &rarr; {@code ActivityQuery}, which bean
 * feeds which endpoint) rather than re-verifying assembly logic covered by their own dedicated tests.
 */
class ConsoleActivityControllerTests {

    private static final class FakeStore implements ReactiveActivityStore {
        ActivityQuery lastQuery;
        ActivityPage pageToReturn = ActivityPage.EMPTY;

        @Override
        public Mono<Void> appendBatch(List<StoredActivityEntry> entries) {
            return Mono.empty();
        }

        @Override
        public Mono<ActivityPage> query(ActivityQuery query) {
            return Mono.just(ActivityPage.EMPTY);
        }

        @Override
        public Mono<ActivityPage> queryAllInstances(ActivityQuery query) {
            this.lastQuery = query;
            return Mono.just(pageToReturn);
        }

        @Override
        public Mono<List<StoredActivityEntry>> queryByCorrelationId(String correlationId, int limit) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<StoredActivityEntry> findByEntryId(String entryId) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> prune(String instanceId, long olderThanEpochMillis) {
            return Mono.empty();
        }
    }

    @Test
    void activityBuildsAnInstanceAgnosticQueryFromRequestParams() {
        FakeStore store = new FakeStore();
        ConsoleActivityController controller = new ConsoleActivityController(
                store, new ConsoleActivityChangeStream(), new ConsoleActivityProperties());

        StepVerifier.create(controller.activity("REQUEST", "ERROR", "hello", 100L, 200L, "cursor-1", 50))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(store.lastQuery.instanceId()).isEmpty();
        assertThat(store.lastQuery.type()).isEqualTo("REQUEST");
        assertThat(store.lastQuery.severity()).isEqualTo("ERROR");
        assertThat(store.lastQuery.text()).isEqualTo("hello");
        assertThat(store.lastQuery.since()).isEqualTo(100L);
        assertThat(store.lastQuery.until()).isEqualTo(200L);
        assertThat(store.lastQuery.cursor()).isEqualTo("cursor-1");
        assertThat(store.lastQuery.pageSize()).isEqualTo(50);
    }

    @Test
    void activityRendersAnEmptyPageAsAnAvailableReportWithNoEntries() {
        FakeStore store = new FakeStore();
        ConsoleActivityProperties properties = new ConsoleActivityProperties();
        properties.setTableName("custom_activity_table");
        ConsoleActivityController controller =
                new ConsoleActivityController(store, new ConsoleActivityChangeStream(), properties);

        StepVerifier.create(controller.activity(null, null, null, null, null, null, 0))
                .assertNext(report -> {
                    assertThat(report.available()).isTrue();
                    assertThat(report.entries()).isEmpty();
                    assertThat(report.persistenceOption().tableName()).isEqualTo("custom_activity_table");
                })
                .verifyComplete();
    }

    @Test
    void activityAssemblesEntriesFromANonEmptyPage() {
        FakeStore store = new FakeStore();
        ActivityEntryDto entryDto = new ActivityEntryDto(
                "e1", "REQUEST", 1_000, "OK", "hi", null, 5L, null, "GET", "/x", 200, "t1", true, null, null, false);
        store.pageToReturn = new ActivityPage(List.of(new StoredActivityEntry("sender-1", 1, entryDto)), null, false);
        ConsoleActivityController controller = new ConsoleActivityController(
                store, new ConsoleActivityChangeStream(), new ConsoleActivityProperties());

        StepVerifier.create(controller.activity(null, null, null, null, null, null, 0))
                .assertNext(report -> {
                    assertThat(report.entries()).hasSize(1);
                    assertThat(report.entries().get(0).summary()).isEqualTo("[sender-1] hi");
                })
                .verifyComplete();
    }

    @Test
    void requestDelegatesToTheProfileAssemblerByEntryId() {
        FakeStore store = new FakeStore();
        ConsoleActivityController controller = new ConsoleActivityController(
                store, new ConsoleActivityChangeStream(), new ConsoleActivityProperties());

        StepVerifier.create(controller.request("missing-id"))
                .assertNext(profile -> {
                    assertThat(profile.available()).isFalse();
                    assertThat(profile.unavailableReason()).contains("missing-id");
                })
                .verifyComplete();
    }

    @Test
    void streamReturnsTheChangeStreamsOwnFlux() {
        // The package-private short-coalesce constructor isn't visible from this (web) package, so this
        // uses the real 750ms default coalesce window and a correspondingly generous verify timeout.
        ConsoleActivityChangeStream changeStream = new ConsoleActivityChangeStream();
        FakeStore store = new FakeStore();
        ConsoleActivityController controller =
                new ConsoleActivityController(store, changeStream, new ConsoleActivityProperties());

        Flux<ServerSentEvent<Map<String, Object>>> stream = controller.stream();

        // StepVerifier subscribes synchronously on create(), so by the time the .then() runnable below
        // runs, the underlying changeStream's subscriber count is already incremented and signal() is live.
        StepVerifier.create(stream)
                .then(changeStream::signal)
                .assertNext(event -> assertThat(event.event()).isEqualTo("update"))
                .then(changeStream::close)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }
}
