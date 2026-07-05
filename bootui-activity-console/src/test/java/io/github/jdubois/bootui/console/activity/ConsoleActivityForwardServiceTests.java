package io.github.jdubois.bootui.console.activity;

import static io.github.jdubois.bootui.console.activity.ConsoleActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityForwardBatchRequest;
import io.github.jdubois.bootui.core.dto.ActivityForwardEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityForwardResponse;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStoreException;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Validation/orchestration tests for {@link ConsoleActivityForwardService#receive}, the reactive
 * counterpart to the engine's {@code ActivityForwardService.receive}. Every validation rule and
 * response shape is asserted byte-identical to the engine version (see {@code
 * ActivityForwardServiceTests} for the blocking original this mirrors), plus the console-specific
 * behavior of signalling {@link ConsoleActivityChangeStream} on a successful append.
 */
class ConsoleActivityForwardServiceTests {

    /** Records every batch actually appended; can be told to fail to simulate a downstream error. */
    private static final class RecordingStore implements ReactiveActivityStore {
        final List<StoredActivityEntry> allAppended = new CopyOnWriteArrayList<>();
        RuntimeException failWith;

        @Override
        public Mono<Void> appendBatch(List<StoredActivityEntry> entries) {
            if (failWith != null) {
                return Mono.error(failWith);
            }
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

    private static ActivityForwardEntryDto forwardEntry(String instanceId, long seq, String id) {
        return new ActivityForwardEntryDto(instanceId, seq, entry(id, "REQUEST", seq, "OK", "hi " + id));
    }

    @Test
    void validBatchIsAppendedAndReturns200WhenNoSecretIsConfigured() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(forwardEntry("sender-1", 1, "e-1"), forwardEntry("sender-1", 2, "e-2")));

        StepVerifier.create(service.receive(null, request))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(200);
                    assertThat(response.body().status()).isEqualTo("accepted");
                    assertThat(response.body().accepted()).isEqualTo(2);
                })
                .verifyComplete();

        assertThat(store.allAppended)
                .extracting(StoredActivityEntry::instanceId)
                .containsExactly("sender-1", "sender-1");
        assertThat(store.allAppended).extracting(e -> e.entry().id()).containsExactly("e-1", "e-2");
    }

    @Test
    void anyOrNoTokenIsAcceptedWhenNoSecretIsConfigured() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        assertStatus(service.receive(null, request), 200);
        assertStatus(service.receive("", request), 200);
        assertStatus(service.receive("whatever-token", request), 200);
    }

    @Test
    void blankConfiguredSecretIsTreatedAsDisabled() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, "   ", new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        assertStatus(service.receive("anything", request), 200);
    }

    @Test
    void missingTokenIsRejectedWhenASecretIsConfiguredAndNeverAppends() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, "configured-secret", new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        StepVerifier.create(service.receive(null, request))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(401);
                    assertThat(response.body().status()).isEqualTo("unauthorized");
                })
                .verifyComplete();
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void wrongTokenIsRejectedWhenASecretIsConfiguredAndNeverAppends() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, "configured-secret", new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        assertStatus(service.receive("wrong-token", request), 401);
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void aMatchingTokenIsAcceptedWhenASecretIsConfigured() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, "configured-secret", new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        assertStatus(service.receive("configured-secret", request), 200);
        assertThat(store.allAppended).hasSize(1);
    }

    @Test
    void aNullRequestBodyIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());

        StepVerifier.create(service.receive(null, null))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(400);
                    assertThat(response.body().status()).isEqualTo("invalid");
                })
                .verifyComplete();
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void anEmptyEntriesListIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());

        assertStatus(service.receive(null, new ActivityForwardBatchRequest(List.of())), 400);
    }

    @Test
    void aBatchExceedingTheMaximumSizeIsRejectedAsInvalidAndNeverAppends() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());
        List<ActivityForwardEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < ConsoleActivityForwardService.MAX_BATCH_SIZE + 1; i++) {
            entries.add(forwardEntry("sender-1", i, "e-" + i));
        }

        StepVerifier.create(service.receive(null, new ActivityForwardBatchRequest(entries)))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(400);
                    assertThat(response.body().message()).contains("exceeds the maximum");
                })
                .verifyComplete();
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void anEntryWithABlankInstanceIdIsRejectedAsInvalidAndNothingIsAppended() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(List.of(
                forwardEntry("sender-1", 1, "e-1"),
                new ActivityForwardEntryDto("  ", 2, entry("e-2", "REQUEST", 2, "OK", "x"))));

        StepVerifier.create(service.receive(null, request))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(400);
                    assertThat(response.body().message()).contains("non-blank instanceId");
                })
                .verifyComplete();
        // Validation runs to completion before anything is appended: not even the first, valid entry lands.
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void anEntryWithANullEntryDtoIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(new ActivityForwardEntryDto("sender-1", 1, null)));

        assertStatus(service.receive(null, request), 400);
    }

    @Test
    void aDownstreamStoreFailureIsMappedToA500WithoutPropagating() {
        RecordingStore store = new RecordingStore();
        store.failWith = new ActivityStoreException("db is down", new RuntimeException());
        ConsoleActivityForwardService service =
                new ConsoleActivityForwardService(store, null, new ConsoleActivityChangeStream());
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        StepVerifier.create(service.receive(null, request))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(500);
                    assertThat(response.body().status()).isEqualTo("failed");
                    assertThat(response.body().message()).contains("db is down");
                })
                .verifyComplete();
    }

    @Test
    void aSuccessfulAppendSignalsTheChangeStream() {
        RecordingStore store = new RecordingStore();
        ConsoleActivityChangeStream changeStream = new ConsoleActivityChangeStream(java.time.Duration.ofMillis(20));
        ConsoleActivityForwardService service = new ConsoleActivityForwardService(store, null, changeStream);
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        // Mirrors ReactiveBootUiChangeStreamTests' own approach for anything beyond the simplest
        // same-tick signal: subscribe with a plain collector and poll for the coalesced flush, rather
        // than chaining more StepVerifier script steps around the async delay.
        List<Object> received = new CopyOnWriteArrayList<>();
        reactor.core.Disposable subscription = changeStream.open().subscribe(received::add);
        try {
            StepVerifier.create(service.receive(null, request))
                    .expectNextCount(1)
                    .verifyComplete();

            long deadline = System.currentTimeMillis() + 2_000;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            assertThat(received).hasSize(1);
        } finally {
            subscription.dispose();
        }
    }

    private static void assertStatus(Mono<ActivityForwardResponse> mono, int expectedStatus) {
        StepVerifier.create(mono)
                .assertNext(response -> assertThat(response.status()).isEqualTo(expectedStatus))
                .verifyComplete();
    }
}
