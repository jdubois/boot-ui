package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The same named-pool discovery with SQL Trace off, where the default datasource bean is the Agroal pool
 * itself. Both modes must publish the same two identities: availability and discovery may not depend on
 * whether the tracing alternative happens to be wrapping.
 */
@QuarkusTest
@TestProfile(BootUiQuarkusNamedDataSourceDiscoveryWithoutSqlTraceTest.UntracedProfile.class)
class BootUiQuarkusNamedDataSourceDiscoveryWithoutSqlTraceTest extends NamedDataSourceDiscoveryAssertions {

    public static final class UntracedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new LinkedHashMap<>(namedH2Datasource());
            overrides.put("bootui.sql-trace.enabled", "false");
            return overrides;
        }
    }

    @Override
    void assertDefaultEntry(DataSource discovered, AgroalDataSource pool) {
        assertThat(discovered)
                .as("with tracing off the alternative hands back the unwrapped pool")
                .isSameAs(pool);
    }
}
