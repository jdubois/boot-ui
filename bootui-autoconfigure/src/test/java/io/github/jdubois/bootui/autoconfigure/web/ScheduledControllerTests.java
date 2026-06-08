package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.*;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link ScheduledController}.
 *
 * <p>Covers infrastructure states: no holder beans (scheduling not present),
 * a present holder with no tasks, a present holder with tasks of various
 * trigger types, and multiple holder beans whose tasks are aggregated.
 * {@link ScheduledTask} is {@code final} with a package-private constructor
 * and is mocked via {@link MockMakers#INLINE}. {@link ScheduledTaskHolder}
 * is an interface and uses the default mock maker.</p>
 */
class ScheduledControllerTests {

    private static <T> T inlineMock(Class<T> cls) {
        return mock(cls, withSettings().mockMaker(MockMakers.INLINE));
    }

    private static ScheduledTaskHolder holderOf(Set<ScheduledTask> tasks) {
        ScheduledTaskHolder holder = mock(ScheduledTaskHolder.class);
        when(holder.getScheduledTasks()).thenReturn(tasks);
        return holder;
    }

    @Test
    void scheduledReturnsAbsentWhenNoHolderBeans() throws Exception {
        MockMvc mvc = standaloneSetup(new ScheduledController(List.of())).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(false))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void scheduledReturnsEmptyListWhenNoTasksRegistered() throws Exception {
        ScheduledTaskHolder holder = holderOf(Set.of());

        MockMvc mvc = standaloneSetup(new ScheduledController(List.of(holder))).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void scheduledReturnsCronTask() throws Exception {
        Runnable runnable = new NamedRunnable("com.example.MyJob#executeJob");
        CronTask cronTask = new CronTask(runnable, "0 0/5 * * * ?");

        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(cronTask);

        ScheduledTaskHolder holder = holderOf(Set.of(scheduledTask));

        MockMvc mvc = standaloneSetup(new ScheduledController(List.of(holder))).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.tasks[0].triggerType").value("CRON"))
                .andExpect(jsonPath("$.tasks[0].expression").value("0 0/5 * * * ?"))
                .andExpect(jsonPath("$.tasks[0].runnable").value("com.example.MyJob#executeJob"));
    }

    @Test
    void scheduledAggregatesTasksFromMultipleHolders() throws Exception {
        ScheduledTask annotationTask = scheduledCronTask(new NamedRunnable("com.example.AnnotatedJob#run"));
        ScheduledTask programmaticTask = scheduledCronTask(new NamedRunnable("com.example.ProgrammaticTimer"));

        ScheduledTaskHolder annotationHolder = holderOf(Set.of(annotationTask));
        ScheduledTaskHolder programmaticHolder = holderOf(Set.of(programmaticTask));

        MockMvc mvc = standaloneSetup(new ScheduledController(List.of(annotationHolder, programmaticHolder)))
                .build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(true))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.tasks[0].runnable").value("com.example.AnnotatedJob#run"))
                .andExpect(jsonPath("$.tasks[1].runnable").value("com.example.ProgrammaticTimer"));
    }

    @Test
    void scheduledFiltersBootUiTasksByDefault() throws Exception {
        ScheduledTask bootUiTask = scheduledCronTask(
                new NamedRunnable("io.github.jdubois.bootui.autoconfigure.web.BootUiMaintenanceTask"));
        ScheduledTask applicationTask = scheduledCronTask(new NamedRunnable("com.example.MyJob#executeJob"));

        ScheduledTaskHolder holder = holderOf(Set.of(bootUiTask, applicationTask));

        MockMvc mvc = standaloneSetup(new ScheduledController(List.of(holder))).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.tasks[0].runnable").value("com.example.MyJob#executeJob"));
    }

    @Test
    void scheduledReturnsFixedRateTask() throws Exception {
        Runnable runnable = new NamedRunnable("com.example.MyPollerTask");
        FixedRateTask fixedRateTask = new FixedRateTask(runnable, Duration.ofSeconds(30), Duration.ofSeconds(5));

        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(fixedRateTask);

        ScheduledTaskHolder holder = holderOf(Set.of(scheduledTask));

        MockMvc mvc = standaloneSetup(new ScheduledController(List.of(holder))).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].triggerType").value("FIXED_RATE"))
                .andExpect(jsonPath("$.tasks[0].expression").value("30000"))
                .andExpect(jsonPath("$.tasks[0].initialDelayMs").value(5000))
                .andExpect(jsonPath("$.tasks[0].timeUnit").value("s"));
    }

    @Test
    void scheduledReturnsFixedDelayTask() throws Exception {
        Runnable runnable = new NamedRunnable("com.example.MyCleanupTask");
        FixedDelayTask fixedDelayTask = new FixedDelayTask(runnable, Duration.ofMillis(500), Duration.ZERO);

        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(fixedDelayTask);

        ScheduledTaskHolder holder = holderOf(Set.of(scheduledTask));

        MockMvc mvc = standaloneSetup(new ScheduledController(List.of(holder))).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].triggerType").value("FIXED_DELAY"))
                .andExpect(jsonPath("$.tasks[0].expression").value("500"))
                .andExpect(jsonPath("$.tasks[0].timeUnit").value("ms"));
    }

    private static ScheduledTask scheduledCronTask(Runnable runnable) {
        CronTask cronTask = new CronTask(runnable, "0 0/5 * * * ?");
        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(cronTask);
        return scheduledTask;
    }

    /**
     * Named runnable whose {@code toString()} returns a meaningful description.
     */
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
