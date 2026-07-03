package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ActivitySwitchServiceTests {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private final ActivitySwitchService service = new ActivitySwitchService();

    private static DataSource newDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:switch-service-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static ActivityPersistenceSettings disabledSettings() {
        return new ActivityPersistenceSettings(
                false,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                Duration.ofDays(7),
                "app-1",
                Duration.ofSeconds(1));
    }

    @Test
    void alreadyPersistentIsIdempotentAndReportsSuccessWithoutRequiringADataSource() {
        DataSource backing = newDataSource();
        SwitchableActivityStore store =
                ActivityStoreFactory.create(disabledSettings().withEnabledSharedMode(), () -> backing);
        try {
            ActivitySwitchResponse response =
                    service.useExistingDataSource(store, disabledSettings(), null, new ActivitySwitchRequest(true));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().status()).isEqualTo("already-active");
            assertThat(response.newSettings()).isNull();
        } finally {
            store.close();
        }
    }

    @Test
    void noDataSourceIsRejectedWith404AndLeavesTheStoreUnchanged() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));

        ActivitySwitchResponse response =
                service.useExistingDataSource(store, disabledSettings(), null, new ActivitySwitchRequest(true));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("unavailable");
        assertThat(response.newSettings()).isNull();
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void missingOrDeclinedConfirmationIsRejectedWith400AndLeavesTheStoreUnchanged() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        DataSource dataSource = newDataSource();

        for (ActivitySwitchRequest request : Arrays.asList(null, new ActivitySwitchRequest(false))) {
            ActivitySwitchResponse response =
                    service.useExistingDataSource(store, disabledSettings(), dataSource, request);

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().status()).isEqualTo("blocked");
            assertThat(response.newSettings()).isNull();
        }
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void confirmedSwitchCreatesTheTableAndMakesTheStorePersistent() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        DataSource dataSource = newDataSource();
        try {
            ActivitySwitchResponse response = service.useExistingDataSource(
                    store, disabledSettings(), dataSource, new ActivitySwitchRequest(true));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().status()).isEqualTo("success");
            assertThat(response.body().tableName()).isEqualTo("bootui_activity");
            // The success message must honestly disclose the runtime-only, not-restart-durable caveat.
            assertThat(response.body().message()).contains("bootui_activity").contains("restart");
            assertThat(response.newSettings()).isNotNull();
            assertThat(response.newSettings().enabled()).isTrue();
            assertThat(response.newSettings().dataSourceMode())
                    .isEqualTo(ActivityPersistenceSettings.DataSourceMode.SHARED);
            assertThat(store.persistent()).isTrue();

            // The table was really created against the supplied DataSource, independent of `store` -
            // querying it through a second, unrelated JdbcActivityStore instance proves this.
            JdbcActivityStore direct = new JdbcActivityStore(dataSource, "bootui_activity");
            assertThat(direct.query(ActivityQuery.firstPage("app-1"))).isEqualTo(ActivityPage.EMPTY);
        } finally {
            store.close();
        }
    }

    @Test
    void schemaVerificationFailureIsReportedAsAnErrorWithoutSwitchingTheStore() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        DataSource broken = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("simulated connection failure");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        ActivitySwitchResponse response =
                service.useExistingDataSource(store, disabledSettings(), broken, new ActivitySwitchRequest(true));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.newSettings()).isNull();
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void raceLossAgainstAConcurrentSwitchReportsAlreadyActiveAndClosesTheUnusedDurableStore() {
        // Simulates two concurrent "Use the existing datasource" requests: this attempt sees a
        // not-yet-persistent store when it checks (so it proceeds to build a durable store), but loses
        // the race when it tries to actually install it, because another attempt won in between.
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        when(store.persistent()).thenReturn(false);
        when(store.attemptSwitchToPersistent(any())).thenReturn(false);
        DataSource dataSource = newDataSource();

        ActivitySwitchResponse response =
                service.useExistingDataSource(store, disabledSettings(), dataSource, new ActivitySwitchRequest(true));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("already-active");
        assertThat(response.newSettings()).isNull();
        verify(store).attemptSwitchToPersistent(any());
    }
}
