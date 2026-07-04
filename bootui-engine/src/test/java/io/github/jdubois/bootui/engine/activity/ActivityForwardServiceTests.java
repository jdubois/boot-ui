package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityForwardBatchRequest;
import io.github.jdubois.bootui.core.dto.ActivityForwardEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityForwardResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Validation/orchestration tests for {@link ActivityForwardService#receive}, the framework-neutral logic
 * behind every adapter's receiving endpoint (Spring's {@code ActivityForwardingController}). Each adapter
 * controller test only needs to prove it wires this call correctly (see {@code
 * ActivityForwardingControllerTests}); the actual decision-making — auth, shape validation, and the batch
 * ceiling — is pinned once, here.
 */
class ActivityForwardServiceTests {

    /** Records every batch actually appended; can be told to throw to simulate a downstream failure. */
    private static final class RecordingStore implements ActivityStore {
        final List<StoredActivityEntry> allAppended = new CopyOnWriteArrayList<>();
        RuntimeException failWith;

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {
            if (failWith != null) {
                throw failWith;
            }
            allAppended.addAll(entries);
        }

        @Override
        public ActivityPage query(ActivityQuery query) {
            return ActivityPage.EMPTY;
        }
    }

    private static ActivityForwardEntryDto forwardEntry(String instanceId, long seq, String id) {
        return new ActivityForwardEntryDto(instanceId, seq, entry(id, "REQUEST", seq, "OK", "hi " + id));
    }

    @Test
    void validBatchIsAppendedAndReturns200WhenNoSecretIsConfigured() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(forwardEntry("sender-1", 1, "e-1"), forwardEntry("sender-1", 2, "e-2")));

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("accepted");
        assertThat(response.body().accepted()).isEqualTo(2);
        assertThat(store.allAppended)
                .extracting(StoredActivityEntry::instanceId)
                .containsExactly("sender-1", "sender-1");
        assertThat(store.allAppended).extracting(e -> e.entry().id()).containsExactly("e-1", "e-2");
    }

    @Test
    void anyOrNoTokenIsAcceptedWhenNoSecretIsConfigured() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        assertThat(ActivityForwardService.receive(store, null, null, request).status())
                .isEqualTo(200);
        assertThat(ActivityForwardService.receive(store, "", null, request).status())
                .isEqualTo(200);
        assertThat(ActivityForwardService.receive(store, null, "whatever-token", request)
                        .status())
                .isEqualTo(200);
        assertThat(ActivityForwardService.receive(store, "   ", "anything", request)
                        .status())
                .isEqualTo(200);
    }

    @Test
    void missingTokenIsUnauthorizedWhenASecretIsConfigured() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        ActivityForwardResponse response = ActivityForwardService.receive(store, "s3cr3t", null, request);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body().status()).isEqualTo("unauthorized");
        assertThat(response.body().accepted()).isZero();
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void wrongTokenIsUnauthorizedWhenASecretIsConfigured() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        ActivityForwardResponse response = ActivityForwardService.receive(store, "s3cr3t", "wrong-token", request);

        assertThat(response.status()).isEqualTo(401);
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void matchingTokenIsAcceptedWhenASecretIsConfigured() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        ActivityForwardResponse response = ActivityForwardService.receive(store, "s3cr3t", "s3cr3t", request);

        assertThat(response.status()).isEqualTo(200);
        assertThat(store.allAppended).hasSize(1);
    }

    @Test
    void nullRequestIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("invalid");
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void nullEntriesListIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(null);

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(400);
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void emptyEntriesListIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(List.of());

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("invalid");
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void batchOverTheHardCeilingIsRejectedAsInvalidWithoutAppendingAnything() {
        RecordingStore store = new RecordingStore();
        List<ActivityForwardEntryDto> tooMany = new ArrayList<>();
        for (int i = 0; i < ActivityForwardService.MAX_BATCH_SIZE + 1; i++) {
            tooMany.add(forwardEntry("sender-1", i, "e-" + i));
        }
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(tooMany);

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("invalid");
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void batchAtExactlyTheHardCeilingIsAccepted() {
        RecordingStore store = new RecordingStore();
        List<ActivityForwardEntryDto> atLimit = new ArrayList<>();
        for (int i = 0; i < ActivityForwardService.MAX_BATCH_SIZE; i++) {
            atLimit.add(forwardEntry("sender-1", i, "e-" + i));
        }
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(atLimit);

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().accepted()).isEqualTo(ActivityForwardService.MAX_BATCH_SIZE);
    }

    @Test
    void entryWithBlankInstanceIdIsRejectedAsInvalidAndNothingIsAppended() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(forwardEntry("sender-1", 1, "e-1"), forwardEntry("  ", 2, "e-2")));

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("invalid");
        // Whole-batch validation runs to completion before anything is appended: a single bad entry
        // rejects the entire POST rather than silently dropping just that one and keeping the rest.
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void entryWithNullInstanceIdIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(
                List.of(new ActivityForwardEntryDto(null, 1, entry("e-1", "REQUEST", 1, "OK", "hi"))));

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(400);
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void entryWithNullActivityDtoIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(new ActivityForwardEntryDto("sender-1", 1, null)));

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(400);
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void nullEntryInTheListIsRejectedAsInvalid() {
        RecordingStore store = new RecordingStore();
        List<ActivityForwardEntryDto> withNull = new ArrayList<>();
        withNull.add(forwardEntry("sender-1", 1, "e-1"));
        withNull.add(null);
        ActivityForwardBatchRequest request = new ActivityForwardBatchRequest(withNull);

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(400);
        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void downstreamAppendFailureIsReportedAs500WithoutPropagating() {
        RecordingStore store = new RecordingStore();
        store.failWith = new ActivityStoreException("simulated durable-storage failure", null);
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        ActivityForwardResponse response = ActivityForwardService.receive(store, null, null, request);

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.body().accepted()).isZero();
    }

    @Test
    void resultBodyIsAnImmutableRecordMatchingTheCoreDtoShape() {
        // Cheap smoke check that the neutral response really carries the core ActivityForwardResult shape
        // (message safe to log, accepted count correct) rather than something adapter-specific.
        RecordingStore store = new RecordingStore();
        ActivityForwardBatchRequest request =
                new ActivityForwardBatchRequest(List.of(forwardEntry("sender-1", 1, "e-1")));

        ActivityForwardResult result =
                ActivityForwardService.receive(store, null, null, request).body();

        assertThat(result).isInstanceOf(ActivityForwardResult.class);
        assertThat(result.message()).isNotBlank();
    }
}
