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
 * Named-pool discovery with SQL Trace on: the prioritized {@code @Alternative} default datasource is the only
 * default the {@code @Any} pass can see, so the named H2 pool is proof that the qualifier pass runs and that
 * physical de-duplication still folds the tracing proxy onto the pool it wraps.
 */
@QuarkusTest
@TestProfile(BootUiQuarkusNamedDataSourceDiscoveryTest.TracedProfile.class)
class BootUiQuarkusNamedDataSourceDiscoveryTest extends NamedDataSourceDiscoveryAssertions {

    public static final class TracedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new LinkedHashMap<>(namedH2Datasource());
            overrides.put("bootui.sql-trace.enabled", "true");
            return overrides;
        }
    }

    @Override
    void assertDefaultEntry(DataSource discovered, AgroalDataSource pool) {
        // The Quarkus producer preserves Agroal's interface, not the Spring datasource marker.
        assertThat(discovered)
                .as("with tracing on the default datasource bean is BootUI's wrapping alternative")
                .isInstanceOf(AgroalDataSource.class)
                .isNotSameAs(pool);
        assertThat(java.lang.reflect.Proxy.isProxyClass(discovered.getClass())).isTrue();
    }
}
