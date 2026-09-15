package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.web.HikariDataSourceDiscovery.HikariPresence;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.AbstractLazyCreationTargetSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The manifest-side contract of {@link HikariDataSourceDiscovery#inspectExisting(DataSource)}. Absence must be
 * proven from the type alone; anything that would require calling into a datasource BootUI does not control, or
 * resolving a target that does not exist yet, is unknown and therefore a candidate.
 */
class HikariDataSourceDiscoveryTests {

    @Test
    void aHikariPoolIsPresentWithoutBeingAskedAnything() {
        HikariDataSource pool = mock(HikariDataSource.class);

        assertThat(HikariDataSourceDiscovery.inspectExisting(pool)).isEqualTo(HikariPresence.PRESENT);

        verifyNoInteractions(pool);
    }

    @Test
    void anAlreadyCreatedStaticAopTargetIsFollowed() {
        HikariDataSource pool = mock(HikariDataSource.class);
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(DataSource.class);
        proxyFactory.setTarget(pool);

        assertThat(HikariDataSourceDiscovery.inspectExisting((DataSource) proxyFactory.getProxy()))
                .isEqualTo(HikariPresence.PRESENT);
    }

    @Test
    void aDynamicAopTargetSourceIsUnknownAndIsNeverResolved() {
        AtomicBoolean resolved = new AtomicBoolean();
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(DataSource.class);
        proxyFactory.setTargetSource(new AbstractLazyCreationTargetSource() {
            @Override
            public Class<?> getTargetClass() {
                return DataSource.class;
            }

            @Override
            protected Object createObject() {
                resolved.set(true);
                return mock(HikariDataSource.class);
            }
        });

        assertThat(HikariDataSourceDiscovery.inspectExisting((DataSource) proxyFactory.getProxy()))
                .isEqualTo(HikariPresence.UNKNOWN);
        assertThat(resolved).isFalse();
    }

    /**
     * An unrecognised wrapper may well delegate to Hikari, but asking would mean running third-party
     * {@code isWrapperFor}/{@code unwrap} on the page-load path, where a lazily built delegate can start a pool.
     */
    @Test
    void anOpaqueWrapperIsUnknownAndIsNeverCalled() throws SQLException {
        DataSource wrapper = mock(DataSource.class);

        assertThat(HikariDataSourceDiscovery.inspectExisting(wrapper)).isEqualTo(HikariPresence.UNKNOWN);

        verify(wrapper, never()).isWrapperFor(HikariDataSource.class);
        verify(wrapper, never()).unwrap(HikariDataSource.class);
        verify(wrapper, never()).getConnection();
    }

    /**
     * Absence is proven by type: a driver-based datasource builds its own connections and can neither be nor
     * contain a Hikari pool. Declaring a URL getter would prove nothing, because wrappers expose those too.
     */
    @Test
    void aKnownTerminalPoolIsAbsent() {
        assertThat(HikariDataSourceDiscovery.inspectExisting(new SimpleDriverDataSource()))
                .isEqualTo(HikariPresence.ABSENT);
    }

    @Test
    void aUserSubclassDoesNotInheritAFalseProofOfAbsence() {
        SimpleDriverDataSource custom = new SimpleDriverDataSource() {};
        assertThat(HikariDataSourceDiscovery.inspectExisting(custom)).isEqualTo(HikariPresence.UNKNOWN);
    }

    @Test
    void aStaticProxyIsLookedThroughWhileNothingAtAllStaysUnknown() {
        ProxyFactory overTerminalPool = new ProxyFactory();
        overTerminalPool.setInterfaces(DataSource.class);
        overTerminalPool.setTarget(new SimpleDriverDataSource());

        assertThat(HikariDataSourceDiscovery.inspectExisting(null)).isEqualTo(HikariPresence.UNKNOWN);
        assertThat(HikariDataSourceDiscovery.inspectExisting((DataSource) overTerminalPool.getProxy()))
                .as("the already-created target settles the question, in either direction")
                .isEqualTo(HikariPresence.ABSENT);
    }
}
