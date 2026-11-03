package io.github.jdubois.bootui.sample.threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ThreadScenariosControllerTests {
    @Test
    void constructionAndStatusStartNoWork() {
        try (var controller = new ThreadScenariosController()) {
            assertThat(controller.status())
                    .isEqualTo(new ThreadScenariosController.Status("none", false, 0, List.of()));
        }
    }

    @Test
    void monitorWaitersHaveTheExplicitOwnerAndBlockedState() {
        try (var controller = new ThreadScenariosController()) {
            controller.start("monitor");
            await(() -> {
                var rows = rows("monitor");
                var owner = rows.stream()
                        .filter(row -> row.getThreadName().endsWith("-owner"))
                        .findFirst();
                return owner.isPresent()
                        && rows.stream()
                                        .filter(row -> row.getThreadName().contains("-waiter-")
                                                && row.getThreadState() == Thread.State.BLOCKED
                                                && row.getLockOwnerId()
                                                        == owner.get().getThreadId())
                                        .count()
                                == 3;
            });
        }
    }

    @Test
    void synchronizerWaitersHaveTheExplicitOwnerAndParkedState() {
        try (var controller = new ThreadScenariosController()) {
            controller.start("synchronizer");
            await(() -> {
                var rows = rows("synchronizer");
                var owner = rows.stream()
                        .filter(row -> row.getThreadName().endsWith("-owner"))
                        .findFirst();
                return owner.isPresent()
                        && rows.stream()
                                        .filter(row -> row.getThreadName().contains("-waiter-")
                                                && row.getThreadState() == Thread.State.WAITING
                                                && row.getLockOwnerId()
                                                        == owner.get().getThreadId())
                                        .count()
                                == 3;
            });
        }
    }

    @Test
    void cpuWorkIsRunnableAndTimedWaitersAreNotExclusiveOwners() {
        try (var controller = new ThreadScenariosController()) {
            controller.start("cpu");
            await(() -> rows("cpu").stream()
                    .anyMatch(row ->
                            row.getThreadName().endsWith("-worker") && row.getThreadState() == Thread.State.RUNNABLE));
            controller.stop();
            controller.start("waiting");
            await(() -> rows("waiting").stream()
                            .filter(row -> row.getThreadName().contains("-worker-")
                                    && row.getThreadState() == Thread.State.TIMED_WAITING
                                    && row.getLockOwnerId() == -1)
                            .count()
                    == 3);
        }
    }

    @Test
    void genuineOwnableLockDeadlockCanBeStoppedWithoutLeakingWorkers() {
        try (var controller = new ThreadScenariosController()) {
            controller.start("deadlock");
            await(() -> {
                long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
                return ids != null
                        && Arrays.stream(ids)
                                        .mapToObj(id -> ManagementFactory.getThreadMXBean()
                                                .getThreadInfo(id))
                                        .filter(row -> row != null
                                                && row.getThreadName().startsWith("sample-threads-deadlock-"))
                                        .count()
                                == 2;
            });
            assertThat(controller.stop().active()).isFalse();
            assertThat(rows("deadlock")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"monitor", "synchronizer", "cpu", "waiting", "deadlock"})
    void everyScenarioExpiresAutomaticallyAndShutdownRefusesNewWork(String scenario) {
        var controller = new ThreadScenariosController(Duration.ofMillis(100));
        try (controller) {
            controller.start(scenario);
            await(() -> !controller.status().active());
            assertThat(rows(scenario)).isEmpty();
        }
        assertThatThrownBy(() -> controller.start(scenario))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void unknownAndOverlappingRequestsFailWithoutReplacingTheActiveScenario() {
        try (var controller = new ThreadScenariosController()) {
            assertThatThrownBy(() -> controller.start("unknown"))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
            var first = controller.start("waiting");
            assertThatThrownBy(() -> controller.start("cpu"))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(controller.status().threads()).isEqualTo(first.threads());
            controller.stop();
            assertThat(controller.start("cpu").scenario()).isEqualTo("cpu");
        }
    }

    private static List<ThreadInfo> rows(String scenario) {
        var bean = ManagementFactory.getThreadMXBean();
        return Arrays.stream(bean.getThreadInfo(bean.getAllThreadIds()))
                .filter(row -> row != null && row.getThreadName().startsWith("sample-threads-" + scenario + "-"))
                .toList();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000L);
        assertThat(condition.getAsBoolean())
                .as("bounded thread scenario reached its expected state")
                .isTrue();
    }
}
