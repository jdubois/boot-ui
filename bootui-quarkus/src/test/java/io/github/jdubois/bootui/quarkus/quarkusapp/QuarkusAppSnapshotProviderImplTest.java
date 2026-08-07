package io.github.jdubois.bootui.quarkus.quarkusapp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkusAppSnapshotProviderImplTest {

    @Test
    void recognizesJdbcDatasourcesOnlyFromTheBuildTimeAgroalFlag() {
        QuarkusAppSnapshot reactive = snapshot(Map.of("quarkus.datasource.db-kind", "postgresql"));
        QuarkusAppSnapshot jdbc = snapshot(Map.of(
                "quarkus.datasource.db-kind",
                "postgresql",
                QuarkusAppSnapshotProviderImpl.JDBC_DATASOURCE_KEY,
                "true"));

        assertThat(reactive.jdbcDatasourcePresent()).isFalse();
        assertThat(jdbc.jdbcDatasourcePresent()).isTrue();
    }

    @Test
    void ignoresUnrelatedSchemaGenerationProperties() {
        QuarkusAppSnapshot unrelated = snapshot(Map.of("vendor.database.generation", "update"));
        QuarkusAppSnapshot legacy =
                snapshot(Map.of("%prod.quarkus.hibernate-orm.\"orders\".database.generation", "update"));

        assertThat(unrelated.legacySchemaGenerationPropertyUsed()).isFalse();
        assertThat(legacy.legacySchemaGenerationPropertyUsed()).isTrue();
    }

    @Test
    void recognizesOnlyTheQuarkusShutdownTimeout() {
        QuarkusAppSnapshot obsoleteHttpKey = snapshot(Map.of("quarkus.http.shutdown.timeout", "PT0S"));
        QuarkusAppSnapshot zeroed = snapshot(Map.of("quarkus.shutdown.timeout", "PT0S"));
        QuarkusAppSnapshot configured = snapshot(Map.of("quarkus.shutdown.timeout", "PT10S"));

        assertThat(obsoleteHttpKey.shutdownTimeoutZeroed()).isFalse();
        assertThat(obsoleteHttpKey.shutdownTimeoutConfigured()).isFalse();
        assertThat(zeroed.shutdownTimeoutZeroed()).isTrue();
        assertThat(zeroed.shutdownTimeoutConfigured()).isTrue();
        assertThat(configured.shutdownTimeoutZeroed()).isFalse();
        assertThat(configured.shutdownTimeoutConfigured()).isTrue();
    }

    @Test
    void ignoresProxyConnectTimeoutWhenInspectingClientTimeouts() {
        QuarkusAppSnapshot proxyTimeout = snapshot(Map.of("quarkus.rest-client.proxy-connect-timeout", "10s"));
        QuarkusAppSnapshot disabledTimeout = snapshot(Map.of("quarkus.rest-client.connect-timeout", "0"));

        assertThat(proxyTimeout.restClientTimeoutZeroOrExcessive()).isFalse();
        assertThat(disabledTimeout.restClientTimeoutZeroOrExcessive()).isTrue();
    }

    private static QuarkusAppSnapshot snapshot(Map<String, String> properties) {
        var config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
        return new QuarkusAppSnapshotProviderImpl(config).snapshot();
    }
}
