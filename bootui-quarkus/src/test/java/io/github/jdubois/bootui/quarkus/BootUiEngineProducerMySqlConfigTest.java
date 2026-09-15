package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.jdubois.bootui.engine.mysql.MySqlRowLimits;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class BootUiEngineProducerMySqlConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void usesSharedDefaultsAndPreservesUnsetLimits() {
        assertThat(BootUiEngineProducer.mySqlRowLimits(config(Map.of()))).isEqualTo(MySqlRowLimits.defaults());
        assertThat(BootUiEngineProducer.mySqlRowLimits(config(Map.of("bootui.mysql.max-tables", "250"))))
                .isEqualTo(new MySqlRowLimits(100, 100, 500, 250, 100, 10, 40));
    }

    @Test
    @SuppressWarnings("unchecked")
    void factoryAndCachedReportNeverResolveEvenLazyDatasourceBeans() {
        SmallRyeConfig config = config(Map.of(
                "bootui.mysql.max-sessions", "11",
                "bootui.mysql.max-statements", "12",
                "bootui.mysql.max-indexes", "13",
                "bootui.mysql.max-tables", "14",
                "bootui.mysql.max-lock-waits", "15",
                "bootui.mysql.max-replication-channels", "16",
                "bootui.mysql.max-settings", "17"));
        Instance<DataSource> dataSources = mock(Instance.class);
        var beanManager = mock(jakarta.enterprise.inject.spi.BeanManager.class);
        var service = new BootUiEngineProducer()
                .mySqlInsightService(dataSources, new QuarkusExposurePolicy(config), config, beanManager);

        assertThat(BootUiEngineProducer.mySqlRowLimits(config))
                .isEqualTo(new MySqlRowLimits(11, 12, 13, 14, 15, 16, 17));
        assertThat(service.report().status()).isEqualTo("NOT_READ");
        assertThat(service.report().dataSources()).isEmpty();
        verifyNoInteractions(dataSources, beanManager);
    }

    @Test
    void invalidLimitsFailAtStartupWithThePropertyNameRatherThanSilentlyClamping() {
        for (String name : List.of(
                "max-sessions",
                "max-statements",
                "max-indexes",
                "max-tables",
                "max-lock-waits",
                "max-replication-channels",
                "max-settings")) {
            for (String invalid : List.of("0", "-1", "2147483647", "2147483648", "1.5", "invalid", "", " ")) {
                String property = "bootui.mysql." + name;
                SmallRyeConfig config = config(Map.of(property, invalid));
                assertThatThrownBy(() -> new BootUiEngineProducer().validateMySqlLimits(null, config))
                        .as("%s=%s", property, invalid)
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining(property);
            }
        }
    }
}
