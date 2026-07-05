package io.github.jdubois.bootui.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.console.activity.ConsoleActivityChangeStream;
import io.github.jdubois.bootui.console.activity.ConsoleActivityForwardService;
import io.github.jdubois.bootui.console.activity.ReactiveActivityStore;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityForwardBatchRequest;
import io.github.jdubois.bootui.core.dto.ActivityForwardEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Wiring-only tests for {@link ConsoleActivityForwardController}: it is a thin adapter over the
 * already fully unit-tested {@link ConsoleActivityForwardService} (see {@code
 * ConsoleActivityForwardServiceTests} for the validation/behavior matrix), so these tests pin only
 * that the controller passes the header/body through correctly and renders the service's status code
 * verbatim &mdash; mirroring {@code ActivityForwardingControllerTests}' plain-instantiation style.
 */
class ConsoleActivityForwardControllerTests {

    private static final class RecordingStore implements ReactiveActivityStore {
        final List<StoredActivityEntry> allAppended = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> appendBatch(List<StoredActivityEntry> entries) {
            allAppended.addAll(entries);
            return Mono.empty();
        }

        @Override
        public Mono<ActivityPage> query(ActivityQuery query) {
            return Mono.just(ActivityPage.EMPTY);
        }

        @Override
        public Mono<ActivityPage> queryAllInstances(ActivityQuery query) {
            return Mono.just(ActivityPage.EMPTY);
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
    void forwardAcceptsAValidBatchAndAppendsItToTheStore() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());
        ConsoleActivityForwardController controller = new ConsoleActivityForwardController(service);
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(List.of(new ActivityForwardEntryDto(
                "sender-1",
                1,
                new ActivityEntryDto(
                        "e-1", "REQUEST", 1_000, "OK", "hi", null, null, null, "GET", "/x", 200, null, true, null, null,
                        false))));

        StepVerifier.create(controller.forward(null, request))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody().status()).isEqualTo("accepted");
                    assertThat(response.getBody().accepted()).isEqualTo(1);
                })
                .verifyComplete();
        assertThat(store.allAppended).hasSize(1);
    }

    @Test
    void forwardRendersTheServicesRejectionStatusVerbatimWithoutAppending() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, "shared-secret", new ConsoleActivityChangeStream());
        ConsoleActivityForwardController controller = new ConsoleActivityForwardController(service);
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(List.of(new ActivityForwardEntryDto(
                "sender-1",
                1,
                new ActivityEntryDto(
                        "e-1", "REQUEST", 1_000, "OK", "hi", null, null, null, "GET", "/x", 200, null, true, null, null,
                        false))));

        StepVerifier.create(controller.forward("wrong-token", request))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(response.getBody().status()).isEqualTo("unauthorized");
                })
                .verifyComplete();
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void forwardRejectsANullBodyAsInvalidWithoutThrowing() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());
        ConsoleActivityForwardController controller = new ConsoleActivityForwardController(service);

        StepVerifier.create(controller.forward(null, null))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getBody().status()).isEqualTo("invalid");
                })
                .verifyComplete();
    }
}
