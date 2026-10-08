package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.journal.ActivityFeedSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code bootui.activity.feed-source=buffers}, removed in 2.0.0, fails the Quarkus start with the message Spring's
 * property binder reports, rather than the first Live Activity request.
 */
class BootUiEngineProducerActivityFeedSourceConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void theFeedSourceIsTheJournalUnlessConfiguredOtherwise() {
        assertThat(BootUiEngineProducer.activityFeedSource(config(Map.of()))).isEqualTo(ActivityFeedSource.JOURNAL);
        assertThat(BootUiEngineProducer.activityFeedSource(config(Map.of(ActivityFeedSource.PROPERTY, "journal"))))
                .isEqualTo(ActivityFeedSource.JOURNAL);
    }

    @Test
    void theRemovedBuffersSourceFailsTheStartupObserverNamingItsReplacement() {
        SmallRyeConfig config = config(Map.of(ActivityFeedSource.PROPERTY, "buffers"));

        assertThatThrownBy(() -> new BootUiEngineProducer().validateActivityFeedSource(null, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ActivityFeedSource.BUFFERS_REMOVED);
    }
}
