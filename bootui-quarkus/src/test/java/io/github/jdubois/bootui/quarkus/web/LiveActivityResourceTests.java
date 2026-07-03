package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.engine.activity.InMemoryActivityStore;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LiveActivityResource}'s persistence wiring: byte-identical behavior when the (always
 * produced, see {@code BootUiEngineProducer}) {@link ActivityPersistenceSettings} bean reports {@code
 * enabled() == false} (the default), and correct entry/pagination delegation plus {@link ActivityQuery}
 * construction when it reports {@code true} — mirroring the Spring adapter's
 * {@code LiveActivityControllerTests}.
 */
class LiveActivityResourceTests {

    @Test
    void activityIsByteIdenticalWhenPersistenceDisabled() {
        LiveActivityResource resource = resourceWith(new InMemoryActivityStore(10), disabledSettings());

        LiveActivityReport result = resource.activity(null, null, null, null, null, null, null, null);

        assertThat(result.pageInfo()).isNull();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void activityDelegatesEntriesAndPageInfoToStoreWhenPersistenceEnabled() {
        ActivityEntryDto storedEntry = new ActivityEntryDto(
                "sql-1", "SQL", 1_000L, "OK", "select 1", null, null, null, null, null, null, null, false, null, null,
                false);
        ActivityPage page =
                new ActivityPage(List.of(new StoredActivityEntry("instance-a", 1L, storedEntry)), "cursor-2", true);
        RecordingActivityStore store = new RecordingActivityStore(page);
        ActivityPersistenceSettings settings = enabledSettings("instance-a");
        LiveActivityResource resource = resourceWith(store, settings);
        LiveActivityReport expectedLive = resource.mergedReport(0);

        LiveActivityReport result = resource.activity(0, "SQL", "OK", "select", 999L, 999L, "cursor-1", 50);

        assertThat(result.entries()).containsExactly(storedEntry);
        assertThat(result.pageInfo()).isEqualTo(new ActivityPageInfo(true, "cursor-2", true));
        // The KPI strip stays a "right now" summary from the full, unfiltered live merge, not scoped to
        // whichever filter or historical page is being browsed (see the class Javadoc).
        assertThat(result.available()).isEqualTo(expectedLive.available());
        assertThat(result.typeCounts()).isEqualTo(expectedLive.typeCounts());
        assertThat(result.kpis()).isEqualTo(expectedLive.kpis());
        assertThat(result.sources()).isEqualTo(expectedLive.sources());
        assertThat(result.warnings()).isEqualTo(expectedLive.warnings());

        assertThat(store.lastQuery).isNotNull();
        assertThat(store.lastQuery.instanceId()).isEqualTo("instance-a");
        assertThat(store.lastQuery.type()).isEqualTo("SQL");
        assertThat(store.lastQuery.severity()).isEqualTo("OK");
        assertThat(store.lastQuery.text()).isEqualTo("select");
        assertThat(store.lastQuery.since()).isEqualTo(999L);
        assertThat(store.lastQuery.until()).isEqualTo(999L);
        assertThat(store.lastQuery.cursor()).isEqualTo("cursor-1");
        assertThat(store.lastQuery.pageSize()).isEqualTo(50);
    }

    @Test
    void sinceOfZeroOrLessTranslatesToUnboundedQuery() {
        RecordingActivityStore store = new RecordingActivityStore(ActivityPage.EMPTY);
        LiveActivityResource resource = resourceWith(store, enabledSettings("instance-a"));

        resource.activity(0, null, null, null, 0L, null, null, null);

        // since<=0 is the existing "no lower bound" convention; the query must translate that to
        // ActivityQuery's own null-means-unbounded convention rather than filtering on since<=0.
        assertThat(store.lastQuery.since()).isNull();
    }

    @Test
    void mergedReportIsUnaffectedByPersistenceFields() {
        LiveActivityResource resource = resourceWith(new InMemoryActivityStore(10), disabledSettings());

        LiveActivityReport report = resource.mergedReport(0);

        assertThat(report).isNotNull();
        assertThat(report.pageInfo()).isNull();
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
                500,
                Duration.ofDays(7),
                "instance-a",
                Duration.ofSeconds(2));
    }

    private static ActivityPersistenceSettings enabledSettings(String instanceId) {
        return new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                500,
                Duration.ofDays(7),
                instanceId,
                Duration.ofSeconds(2));
    }

    private static LiveActivityResource resourceWith(
            ActivityStore activityStore, ActivityPersistenceSettings settings) {
        Config config = config(Map.of());
        return new LiveActivityResource(
                new HttpExchangeBuffer(50),
                new QuarkusExposurePolicy(config),
                unsatisfiedSqlTraceRecorder(),
                new ExceptionStore(10, 10, 10),
                new ExceptionsService(new QuarkusExposurePolicy(config)),
                new SecurityEventBuffer(10),
                new QuarkusPanelAvailability(config),
                null, // TracesService: unused by activity()/mergedReport(), only by the request() drill-down
                activityStore,
                settings);
    }

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    private static Instance<SqlTraceRecorder> unsatisfiedSqlTraceRecorder() {
        return new UnsatisfiedInstance<>();
    }

    /** Records the last {@link ActivityQuery} it was asked and answers a fixed page, like a test spy. */
    private static final class RecordingActivityStore implements ActivityStore {

        private final ActivityPage page;
        private ActivityQuery lastQuery;

        RecordingActivityStore(ActivityPage page) {
            this.page = page;
        }

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {
            throw new UnsupportedOperationException("not exercised by these tests");
        }

        @Override
        public ActivityPage query(ActivityQuery query) {
            this.lastQuery = query;
            return page;
        }
    }

    /**
     * A minimal always-unsatisfied {@link Instance}, standing in for an absent {@code SqlTraceRecorder}
     * bean (no datasource configured). No Mockito dependency exists in this module, and CDI {@link
     * Instance} resolution glue is otherwise proven end-to-end by {@code @QuarkusTest} integration
     * coverage rather than mocked here, matching this module's established practice.
     */
    private static final class UnsatisfiedInstance<T> implements Instance<T> {

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return true;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            throw new UnsatisfiedResolutionException("no SqlTraceRecorder bean produced in this test");
        }
    }
}
