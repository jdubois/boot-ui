package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bound on pending executor snapshots: long-delayed queued tasks never grow a registry past its cap. */
class TaskSnapshotsTests {

    private static final long GENERATION = 1L;

    @Test
    void retainedTasksBeyondTheCapAreRefusedCountedAndRunUnowned() {
        TaskSnapshots snapshots = new TaskSnapshots(4);
        List<Object> queued = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Object task = new Object();
            queued.add(task);
            assertThat(snapshots.put(task, GENERATION, owner("r" + i), 0L)).isEqualTo(TaskSnapshots.OWNED);
        }
        Object late = new Object();

        assertThat(snapshots.put(late, GENERATION, owner("r9"), 0L)).isEqualTo(TaskSnapshots.REFUSED);
        assertThat(snapshots.put(new Object(), GENERATION, owner("r9"), 0L)).isEqualTo(TaskSnapshots.REFUSED);

        // The caller counts a refusal once the executor accepted the task.
        assertThat(snapshots.overflow()).isZero();
        snapshots.overflowed();
        assertThat(snapshots.overflow()).isEqualTo(1);
        assertThat(snapshots.size()).isEqualTo(4);
        assertThat(snapshots.entries.get()).isEqualTo(4);
        // Refused, so recorded nowhere: it runs unowned.
        assertThat(snapshots.take(late)).isNull();
        assertThat(snapshots.peek(late)).isNull();
        // The queued tasks keep their owners.
        assertThat(((TaskSnapshots.Entry) snapshots.take(queued.get(0))).payload[0])
                .isEqualTo("r0");
        assertThat(snapshots.entries.get()).isEqualTo(3);
        // The room a run freed is taken by the next submission.
        assertThat(snapshots.put(late, GENERATION, owner("r9"), 0L)).isEqualTo(TaskSnapshots.OWNED);
        assertThat(snapshots.size()).isEqualTo(4);
    }

    @Test
    void aFullRegistryKeepsTheAmbiguityRulesOfTasksItAlreadyHolds() {
        TaskSnapshots snapshots = new TaskSnapshots(2);
        Object shared = new Object();
        Object other = new Object();
        snapshots.put(shared, GENERATION, owner("r1"), 0L);
        snapshots.put(other, GENERATION, owner("r2"), 0L);

        // Another submission of a task the registry holds is never refused: another owner still makes it ambiguous.
        assertThat(snapshots.put(shared, GENERATION, owner("r3"), 0L)).isEqualTo(TaskSnapshots.AMBIGUOUS_PUT);
        assertThat(snapshots.putUnowned(other)).isTrue();

        assertThat(snapshots.overflow()).isZero();
        assertThat(snapshots.take(shared)).isSameAs(TaskSnapshots.AMBIGUOUS);
        assertThat(snapshots.take(shared)).isSameAs(TaskSnapshots.AMBIGUOUS);
        assertThat(snapshots.take(shared)).isNull();
        assertThat(snapshots.take(other)).isSameAs(TaskSnapshots.AMBIGUOUS);
        assertThat(snapshots.take(other)).isSameAs(TaskSnapshots.AMBIGUOUS);
        assertThat(snapshots.entries.get()).isZero();
    }

    @Test
    void aRefusedSubmissionLeavesTheTaskLikeAnUnownedFirstSubmission() {
        TaskSnapshots snapshots = new TaskSnapshots(1);
        Object filler = new Object();
        Object task = new Object();
        snapshots.put(filler, GENERATION, owner("r1"), 0L);

        assertThat(snapshots.put(task, GENERATION, owner("r2"), 0L)).isEqualTo(TaskSnapshots.REFUSED);
        snapshots.take(filler);
        assertThat(snapshots.put(task, GENERATION, owner("r3"), 0L)).isEqualTo(TaskSnapshots.OWNED);

        // The accepted limit, documented: the first run takes the snapshot of the submission that was admitted.
        assertThat(((TaskSnapshots.Entry) snapshots.take(task)).payload[0]).isEqualTo("r3");
        assertThat(snapshots.take(task)).isNull();
    }

    @Test
    void selfTestMarkersAreAdmittedPastTheCap() {
        TaskSnapshots snapshots = new TaskSnapshots(1);
        snapshots.put(new Object(), GENERATION, owner("r1"), 0L);
        Object marker = new Object();

        assertThat(snapshots.putSelfTest(marker, GENERATION, owner("self"))).isEqualTo(TaskSnapshots.OWNED);

        assertThat(snapshots.overflow()).isZero();
        assertThat(snapshots.peek(marker)).isNotNull();
        assertThat(snapshots.entries.get()).isEqualTo(2);
        snapshots.take(marker);
        assertThat(snapshots.entries.get()).isEqualTo(1);
    }

    @Test
    void reclaimedTasksAreExpungedBeforeAdmissionInBoundedBatches() throws Exception {
        int reclaimed = TaskSnapshots.EXPUNGE_BATCH * 3;
        TaskSnapshots snapshots = new TaskSnapshots(reclaimed);
        awaitCollected(fillWithGarbage(snapshots, reclaimed));

        Object task = new Object();

        // Full of reclaimed tasks: a submission expunges them first, so it is admitted. An attempt is refused only
        // while no reclaimed task has been enqueued yet, so the one admitted expunged between 1 and a batch of them.
        int admitted = TaskSnapshots.REFUSED;
        for (int i = 0; i < 100 && admitted == TaskSnapshots.REFUSED; i++) {
            Thread.sleep(10);
            admitted = snapshots.put(task, GENERATION, owner("r1"), 0L);
        }
        assertThat(admitted).isEqualTo(TaskSnapshots.OWNED);
        assertThat(snapshots.entries.get()).isBetween(reclaimed - TaskSnapshots.EXPUNGE_BATCH + 1, reclaimed);
        // The status read expunges every reclaimed task.
        for (int i = 0; i < 100 && snapshots.size() > 1; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertThat(snapshots.size()).isEqualTo(1);
        assertThat(snapshots.entries.get()).isEqualTo(1);
        assertThat(snapshots.neverApplied()).isEqualTo(reclaimed);
    }

    @Test
    void resetDropsEveryEntryAndKeepsTheAdmissionCountExact() {
        TaskSnapshots snapshots = new TaskSnapshots(2);
        Object first = new Object();
        snapshots.put(first, GENERATION, owner("r1"), 0L);
        snapshots.put(new Object(), GENERATION, owner("r2"), 0L);
        snapshots.put(new Object(), GENERATION, owner("r3"), 0L);
        snapshots.overflowed();

        snapshots.reset();

        assertThat(snapshots.entries.get()).isZero();
        assertThat(snapshots.overflow()).isZero();
        assertThat(snapshots.take(first)).isNull();
        assertThat(snapshots.put(new Object(), GENERATION, owner("r4"), 0L)).isEqualTo(TaskSnapshots.OWNED);
        assertThat(snapshots.put(new Object(), GENERATION, owner("r5"), 0L)).isEqualTo(TaskSnapshots.OWNED);
    }

    private static List<WeakReference<Object>> fillWithGarbage(TaskSnapshots snapshots, int count) {
        List<WeakReference<Object>> references = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object task = new Object();
            references.add(new WeakReference<>(task));
            assertThat(snapshots.put(task, GENERATION, owner("g" + i), 0L)).isEqualTo(TaskSnapshots.OWNED);
        }
        assertThat(snapshots.put(new Object(), GENERATION, owner("g"), 0L)).isEqualTo(TaskSnapshots.REFUSED);
        return references;
    }

    private static void awaitCollected(List<WeakReference<Object>> references) throws InterruptedException {
        for (int i = 0; i < 100 && references.stream().anyMatch(reference -> reference.get() != null); i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(references)
                .allSatisfy(
                        reference -> assertThat(reference.get()).as("collected").isNull());
    }

    private static Object[] owner(String requestId) {
        return new Object[] {requestId, null, null, null, null, null, null, 1L, 1L};
    }
}
