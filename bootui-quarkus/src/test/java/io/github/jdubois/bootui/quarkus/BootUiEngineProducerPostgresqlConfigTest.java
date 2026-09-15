package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.jdubois.bootui.engine.postgres.PostgresRowLimits;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class BootUiEngineProducerPostgresqlConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void usesSharedDefaultsAndPreservesUnsetLimits() {
        assertThat(BootUiEngineProducer.postgresRowLimits(config(Map.of()))).isEqualTo(PostgresRowLimits.defaults());
        assertThat(BootUiEngineProducer.postgresRowLimits(config(Map.of("bootui.postgresql.max-tables", "250"))))
                .isEqualTo(new PostgresRowLimits(100, 100, 500, 250, 200, 10, 40));
    }

    @Test
    @SuppressWarnings("unchecked")
    void factoryMapsEveryLimitWithoutReadingTheDatabase() {
        SmallRyeConfig config = config(Map.of(
                "bootui.postgresql.max-sessions", "11",
                "bootui.postgresql.max-statements", "12",
                "bootui.postgresql.max-indexes", "13",
                "bootui.postgresql.max-tables", "14",
                "bootui.postgresql.max-vacuum-tables", "15",
                "bootui.postgresql.max-replicas", "16",
                "bootui.postgresql.max-settings", "17"));
        Instance<DataSource> dataSources = mock(Instance.class);
        var service = new BootUiEngineProducer()
                .postgresInsightService(dataSources, mock(QuarkusExposurePolicy.class), config, null);

        assertThat(service)
                .extracting(
                        "limits.maxSessions",
                        "limits.maxStatements",
                        "limits.maxIndexes",
                        "limits.maxTables",
                        "limits.maxVacuumTables",
                        "limits.maxReplicas",
                        "limits.maxSettings")
                .containsExactly(11, 12, 13, 14, 15, 16, 17);
        assertThat(service.initialReport().status()).isEqualTo("NOT_READ");
        verifyNoInteractions(dataSources);
    }

    @Test
    void invalidLimitsFailAtStartupRatherThanFallingBackToDefaults() {
        for (String name : List.of(
                "max-sessions",
                "max-statements",
                "max-indexes",
                "max-tables",
                "max-vacuum-tables",
                "max-replicas",
                "max-settings")) {
            for (String invalid : List.of("0", "-1", "2147483647", "2147483648", "1.5", "invalid", "", " ")) {
                String property = "bootui.postgresql." + name;
                SmallRyeConfig config = config(Map.of(property, invalid));
                assertThatThrownBy(() -> new BootUiEngineProducer().validatePostgresqlLimits(null, config))
                        .as("%s=%s", property, invalid)
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining(property);
            }
        }
    }
}
