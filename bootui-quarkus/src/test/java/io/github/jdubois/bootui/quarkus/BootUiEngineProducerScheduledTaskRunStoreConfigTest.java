package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code bootui.activity.max-scheduled-task-runs} MicroProfile {@link Config} binding used by
 * {@link BootUiEngineProducer#scheduledTaskRunStore(Config)}. The key name and default (200) are kept
 * unified with the Spring adapter's {@code BootUiProperties.Activity.getMaxScheduledTaskRuns()}, so the
 * same config key sizes the buffer identically on both frameworks (see {@code docs/PLAN.md} §3.4).
 */
class BootUiEngineProducerScheduledTaskRunStoreConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void appliesUnifiedDefaultOf200WhenUnset() {
        ScheduledTaskRunStore store = new BootUiEngineProducer().scheduledTaskRunStore(config(Map.of()));

        fillBeyondCapacity(store, 201);

        assertThat(store.runs()).hasSize(200);
    }

    @Test
    void bindsMaxScheduledTaskRunsOverride() {
        ScheduledTaskRunStore store = new BootUiEngineProducer()
                .scheduledTaskRunStore(config(Map.of("bootui.activity.max-scheduled-task-runs", "3")));

        fillBeyondCapacity(store, 5);

        assertThat(store.runs()).hasSize(3);
    }

    private static void fillBeyondCapacity(ScheduledTaskRunStore store, int count) {
        for (int i = 0; i < count; i++) {
            store.record("com.example.Job#run", i, 1L, true, null, null, "worker-1");
        }
    }
}
