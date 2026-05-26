package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link ScheduledController}.
 *
 * <p>Covers three infrastructure states: absent holder (scheduling not
 * present), present holder with no tasks, and present holder with tasks
 * of various trigger types. {@link ScheduledTask} is {@code final} with a
 * package-private constructor and is mocked via {@link MockMakers#INLINE}.
 * {@link ScheduledTaskHolder} is an interface and uses the default mock
 * maker.</p>
 */
class ScheduledControllerTests {

    @Test
    void scheduledReturnsAbsentWhenHolderUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new ScheduledController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(false))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void scheduledReturnsEmptyListWhenNoTasksRegistered() throws Exception {
        ScheduledTaskHolder holder = mock(ScheduledTaskHolder.class);
        when(holder.getScheduledTasks()).thenReturn(Set.of());

        MockMvc mvc = standaloneSetup(new ScheduledController(providerOf(holder))).build();

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

        ScheduledTaskHolder holder = mock(ScheduledTaskHolder.class);
        when(holder.getScheduledTasks()).thenReturn(Set.of(scheduledTask));

        MockMvc mvc = standaloneSetup(new ScheduledController(providerOf(holder))).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.tasks[0].triggerType").value("CRON"))
                .andExpect(jsonPath("$.tasks[0].expression").value("0 0/5 * * * ?"))
                .andExpect(jsonPath("$.tasks[0].runnable").value("com.example.MyJob#executeJob"));
    }

    @Test
    void scheduledReturnsFixedRateTask() throws Exception {
        Runnable runnable = new NamedRunnable("com.example.MyPollerTask");
        FixedRateTask fixedRateTask = new FixedRateTask(runnable,
                Duration.ofSeconds(30), Duration.ofSeconds(5));

        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(fixedRateTask);

        ScheduledTaskHolder holder = mock(ScheduledTaskHolder.class);
        when(holder.getScheduledTasks()).thenReturn(Set.of(scheduledTask));

        MockMvc mvc = standaloneSetup(new ScheduledController(providerOf(holder))).build();

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
        FixedDelayTask fixedDelayTask = new FixedDelayTask(runnable,
                Duration.ofMillis(500), Duration.ZERO);

        ScheduledTask scheduledTask = inlineMock(ScheduledTask.class);
        when(scheduledTask.getTask()).thenReturn(fixedDelayTask);

        ScheduledTaskHolder holder = mock(ScheduledTaskHolder.class);
        when(holder.getScheduledTasks()).thenReturn(Set.of(scheduledTask));

        MockMvc mvc = standaloneSetup(new ScheduledController(providerOf(holder))).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].triggerType").value("FIXED_DELAY"))
                .andExpect(jsonPath("$.tasks[0].expression").value("500"))
                .andExpect(jsonPath("$.tasks[0].timeUnit").value("ms"));
    }

    private static <T> T inlineMock(Class<T> cls) {
        return mock(cls, withSettings().mockMaker(MockMakers.INLINE));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ScheduledTaskHolder> providerOf(ScheduledTaskHolder holder) {
        ObjectProvider<ScheduledTaskHolder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(holder);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ScheduledTaskHolder> emptyProvider() {
        ObjectProvider<ScheduledTaskHolder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
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
