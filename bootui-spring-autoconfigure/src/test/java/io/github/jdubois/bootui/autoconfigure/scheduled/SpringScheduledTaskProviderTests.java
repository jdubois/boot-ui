package io.github.jdubois.bootui.autoconfigure.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.ScheduledTaskDto;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Tests for {@link SpringScheduledTaskProvider}: the {@code org.springframework.scheduling.config.*} trigger
 * model → {@link ScheduledTaskDto} mapping and the BootUI self-data filter, relocated verbatim from the
 * original {@code ScheduledControllerTests}.
 *
 * <p>The provider returns the mapped, self-filtered tasks <em>unsorted</em> (the engine
 * {@code ScheduledTasksService} owns ordering and the {@code schedulingPresent}/{@code total} wrapping), so
 * multi-task assertions are order-independent. {@link ScheduledTask} is {@code final} with a package-private
 * constructor and is mocked via {@link MockMakers#INLINE}; {@link ScheduledTaskHolder} is an interface and
 * uses the default mock maker.</p>
 */
class SpringScheduledTaskProviderTests {

    private static <T> T inlineMock(Class<T> cls) {
        return mock(cls, withSettings().mockMaker(MockMakers.INLINE));
    }

    private static ScheduledTaskHolder holderOf(Set<ScheduledTask> tasks) {
        ScheduledTaskHolder holder = mock(ScheduledTaskHolder.class);
        when(holder.getScheduledTasks()).thenReturn(tasks);
        return holder;
    }

    private static SpringScheduledTaskProvider providerOf(List<ScheduledTaskHolder> holders) {
        return new SpringScheduledTaskProvider(holders, BootUiSelfDataFilter.defaults());
    }

    @Test
    void unavailableWhenNoHolderBeans() {
        SpringScheduledTaskProvider provider = providerOf(List.of());

        assertThat(provider.available()).isFalse();
        assertThat(provider.tasks()).isEmpty();
    }

    @Test
    void availableWithEmptyTasksWhenHolderHasNoTasks() {
        SpringScheduledTaskProvider provider = providerOf(List.of(holderOf(Set.of())));

        assertThat(provider.available()).isTrue();
        assertThat(provider.tasks()).isEmpty();
    }

    @Test
    void mapsCronTask() {
        Runnable runnable = new NamedRunnable("com.example.MyJob#executeJob");
        CronTask cronTask = new CronTask(runnable, "0 0/5 * * * ?");
        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(cronTask);

        SpringScheduledTaskProvider provider = providerOf(List.of(holderOf(Set.of(scheduledTask))));

        assertThat(provider.tasks())
                .extracting(ScheduledTaskDto::runnable, ScheduledTaskDto::triggerType, ScheduledTaskDto::expression)
                .containsExactly(Tuple.tuple("com.example.MyJob#executeJob", "CRON", "0 0/5 * * * ?"));
    }

    @Test
    void mapsFixedRateTask() {
        Runnable runnable = new NamedRunnable("com.example.MyPollerTask");
        FixedRateTask fixedRateTask = new FixedRateTask(runnable, Duration.ofSeconds(30), Duration.ofSeconds(5));
        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(fixedRateTask);

        SpringScheduledTaskProvider provider = providerOf(List.of(holderOf(Set.of(scheduledTask))));

        assertThat(provider.tasks())
                .extracting(
                        ScheduledTaskDto::triggerType,
                        ScheduledTaskDto::expression,
                        ScheduledTaskDto::initialDelayMs,
                        ScheduledTaskDto::timeUnit)
                .containsExactly(Tuple.tuple("FIXED_RATE", "30000", 5000L, "s"));
    }

    @Test
    void mapsFixedDelayTask() {
        Runnable runnable = new NamedRunnable("com.example.MyCleanupTask");
        FixedDelayTask fixedDelayTask = new FixedDelayTask(runnable, Duration.ofMillis(500), Duration.ZERO);
        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(fixedDelayTask);

        SpringScheduledTaskProvider provider = providerOf(List.of(holderOf(Set.of(scheduledTask))));

        assertThat(provider.tasks())
                .extracting(ScheduledTaskDto::triggerType, ScheduledTaskDto::expression, ScheduledTaskDto::timeUnit)
                .containsExactly(Tuple.tuple("FIXED_DELAY", "500", "ms"));
    }

    @Test
    void aggregatesTasksFromMultipleHolders() {
        ScheduledTask annotationTask = cronTaskOf(new NamedRunnable("com.example.AnnotatedJob#run"));
        ScheduledTask programmaticTask = cronTaskOf(new NamedRunnable("com.example.ProgrammaticTimer"));

        SpringScheduledTaskProvider provider =
                providerOf(List.of(holderOf(Set.of(annotationTask)), holderOf(Set.of(programmaticTask))));

        assertThat(provider.tasks())
                .extracting(ScheduledTaskDto::runnable)
                .containsExactlyInAnyOrder("com.example.AnnotatedJob#run", "com.example.ProgrammaticTimer");
    }

    @Test
    void filtersBootUiTasksByDefault() {
        ScheduledTask bootUiTask =
                cronTaskOf(new NamedRunnable("io.github.jdubois.bootui.autoconfigure.web.BootUiMaintenanceTask"));
        ScheduledTask applicationTask = cronTaskOf(new NamedRunnable("com.example.MyJob#executeJob"));

        SpringScheduledTaskProvider provider = providerOf(List.of(holderOf(Set.of(bootUiTask, applicationTask))));

        assertThat(provider.tasks())
                .extracting(ScheduledTaskDto::runnable)
                .containsExactly("com.example.MyJob#executeJob");
    }

    private static ScheduledTask cronTaskOf(Runnable runnable) {
        CronTask cronTask = new CronTask(runnable, "0 0/5 * * * ?");
        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(cronTask);
        return scheduledTask;
    }

    /** Named runnable whose {@code toString()} returns a meaningful description. */
    private static final class NamedRunnable implements Runnable {
        private final String name;

        NamedRunnable(String name) {
            this.name = name;
        }

        @Override
        public void run() {}

        @Override
        public String toString() {
            return name;
        }
    }
}
