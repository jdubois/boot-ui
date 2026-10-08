package io.github.jdubois.bootui.engine.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperationProgressTests {

    private static final ProgressPhase PHASE = ProgressPhase.of("Working");

    @Test
    void onlyFiniteStrictlyIncreasingProgressReachesTheListener() {
        List<ProgressEvent> events = new ArrayList<>();
        OperationProgress progress = new OperationProgress(events::add);

        progress.report(PHASE, 1, 4);
        progress.report(PHASE, 1, 4);
        progress.report(PHASE, 0.5, 4);
        progress.report(PHASE, Double.NaN, 4);
        progress.report(PHASE, Double.POSITIVE_INFINITY, 4);
        progress.report(PHASE, 2, Double.NaN);
        progress.report(PHASE, 3, 0);
        progress.report(PHASE, 3.5, -1);

        assertThat(events)
                .containsExactly(
                        new ProgressEvent(1, 4.0, "Working"),
                        new ProgressEvent(2, null, "Working"),
                        new ProgressEvent(3, null, "Working"),
                        new ProgressEvent(3.5, null, "Working"));
    }

    @Test
    void cancellationStopsReportsAndIsVisibleToCheckpoints() {
        List<ProgressEvent> events = new ArrayList<>();
        OperationProgress progress = new OperationProgress(events::add);
        assertThat(progress.cancelled()).isFalse();
        progress.checkCancelled();

        progress.cancel();

        progress.report(PHASE, 1, 1);
        assertThat(events).isEmpty();
        assertThat(progress.cancelled()).isTrue();
        assertThatThrownBy(progress::checkCancelled).isInstanceOf(OperationCancelledException.class);
    }

    @Test
    void noneIgnoresReportsAndCannotBeCancelledButHonoursInterrupts() {
        OperationProgress.NONE.report(PHASE, 1, 1);
        OperationProgress.NONE.cancel();
        assertThat(OperationProgress.NONE.reportsProgress()).isFalse();
        assertThat(OperationProgress.NONE.cancelled()).isFalse();

        Thread.currentThread().interrupt();
        try {
            assertThat(OperationProgress.NONE.cancelled()).isTrue();
            assertThatThrownBy(OperationProgress.NONE::checkCancelled).isInstanceOf(OperationCancelledException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void runWithBindsTheCurrentThreadOnlyAndRestoresTheOuterBinding() throws Exception {
        OperationProgress outer = new OperationProgress(event -> {});
        OperationProgress inner = new OperationProgress(event -> {});
        assertThat(OperationProgress.current()).isSameAs(OperationProgress.NONE);

        OperationProgress.runWith(outer, () -> {
            assertThat(OperationProgress.current()).isSameAs(outer);
            OperationProgress[] other = new OperationProgress[1];
            Thread thread = new Thread(() -> other[0] = OperationProgress.current());
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException ex) {
                throw new AssertionError(ex);
            }
            assertThat(other[0]).isSameAs(OperationProgress.NONE);
            assertThatThrownBy(() -> OperationProgress.runWith(inner, () -> {
                        assertThat(OperationProgress.current()).isSameAs(inner);
                        throw new IllegalStateException("boom");
                    }))
                    .hasMessage("boom");
            assertThat(OperationProgress.current()).isSameAs(outer);
            return null;
        });

        assertThat(OperationProgress.current()).isSameAs(OperationProgress.NONE);
    }

    @Test
    void phaseLabelsAreFixedPlainText() {
        assertThat(ProgressPhase.of("Querying OSV (batch 2 of 5)").label()).isEqualTo("Querying OSV (batch 2 of 5)");
        for (String label : List.of(
                "",
                " leading space",
                "/Users/admin/app.jar",
                "jdbc:postgresql://db/app",
                "SELECT * FROM users",
                "password=hunter2",
                "line\nbreak",
                "x".repeat(81))) {
            assertThatThrownBy(() -> ProgressPhase.of(label)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
