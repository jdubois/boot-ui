package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed.Filter;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The live exposure rule for journal-rendered text ({@code PLAN-v2} §8): SQL, log text, and paths. */
class JournalTextExposureTests {

    private static final Function<JournalEntry, String> EVENT_ID = entry -> "run-" + entry.sequence();

    private static final String SQL = "select * from users where name = 'alice' and token = \"s3cr3t\"";
    private static final String LOG = "Login with password=hunter2 refused";
    private static final String PATH = "/orders;token=abc123";

    private final MutablePolicy policy = new MutablePolicy();
    private final List<JournalEntry> entries = new ArrayList<>();
    private final JournalActivityFeed feed = new JournalActivityFeed(
            1_000,
            3,
            () -> RouteTemplateResolver.of(List.of(new MappingDto("GET", "/orders", "h", null, null))),
            policy);

    @Test
    void aLiveSwitchFromFullToMaskedToMetadataOnlyAppliesToTheNextRenderOfTheSameFeed() {
        record("r1", JournalSource.SQL, new SqlPayload(SQL, null, "dataSource", false), 1_000);
        record("r1", JournalSource.LOG, new LogPayload("com.acme.Login", "WARN", LOG, null), 1_001);
        record("r1", JournalSource.HTTP, new HttpPayload("GET", PATH, "/orders", null, 200), 1_002);

        policy.set(ValueExposure.FULL, true);
        List<ActivityEntryDto> full = render();
        assertThat(only(full, "SQL").summary()).isEqualTo(SQL);
        assertThat(only(full, "LOG").summary()).isEqualTo(LOG);
        assertThat(only(full, "REQUEST").path()).isEqualTo(PATH);
        assertThat(only(full, "REQUEST").summary()).isEqualTo("GET " + PATH + " → 200");

        policy.set(ValueExposure.MASKED, true);
        List<ActivityEntryDto> masked = render();
        assertThat(only(masked, "SQL").summary())
                .isEqualTo("select * from users where name = ? and token = ?")
                .doesNotContain("alice", "s3cr3t");
        assertThat(only(masked, "LOG").summary()).contains("password=").doesNotContain("hunter2");
        assertThat(only(masked, "REQUEST").path()).startsWith("/orders;token=").doesNotContain("abc123");
        assertThat(only(masked, "REQUEST").summary()).doesNotContain("abc123");

        policy.set(ValueExposure.METADATA_ONLY, true);
        List<ActivityEntryDto> metadata = render();
        assertThat(only(metadata, "SQL").summary()).doesNotContain("alice", "s3cr3t");
        assertThat(only(metadata, "LOG").summary()).isEmpty();
        assertThat(only(metadata, "REQUEST").path()).doesNotContain("abc123");
        assertThat(feed.kpis(entries, null).toString()).doesNotContain("abc123");
    }

    @Test
    void aMaskSecretsToggleAloneAppliesToTheNextRender() {
        record("r1", JournalSource.SQL, new SqlPayload(SQL, null, "dataSource", false), 1_000);
        record("r1", JournalSource.HTTP, new HttpPayload("GET", "/orders", "/orders", null, 200), 1_001);

        policy.set(ValueExposure.MASKED, false);
        assertThat(only(render(), "SQL").summary()).isEqualTo(SQL);

        policy.set(ValueExposure.MASKED, true);
        assertThat(only(render(), "SQL").summary()).doesNotContain("alice", "s3cr3t");
    }

    @Test
    void persistenceIsRenderedMaskedEvenWhenTheLivePolicyIsFull() {
        policy.set(ValueExposure.FULL, true);
        record("r1", JournalSource.SQL, new SqlPayload(SQL, null, "dataSource", false), 1_000);
        record("r1", JournalSource.LOG, new LogPayload("com.acme.Login", "WARN", LOG, null), 1_001);
        record("r1", JournalSource.HTTP, new HttpPayload("GET", PATH, "/orders", null, 200), 1_002);

        List<ActivityEntryDto> captured = feed.renderForCapture(entries, EVENT_ID, new HashMap<>());

        assertThat(captured)
                .allSatisfy(row -> assertThat(String.valueOf(row.summary()) + row.path())
                        .doesNotContain("alice", "s3cr3t", "hunter2", "abc123"));
    }

    @Test
    void theRequestProfileFollowsTheLivePolicy() {
        record("r1", JournalSource.SQL, new SqlPayload(SQL, null, "dataSource", false), 1_000);
        record("r1", JournalSource.LOG, new LogPayload("com.acme.Login", "WARN", LOG, null), 1_001);
        record("r1", JournalSource.HTTP, new HttpPayload("GET", PATH, "/orders", null, 200), 1_002);
        RuntimeJournal journal = journalOf(entries);
        try {
            RequestJournalProfiles profiles =
                    new RequestJournalProfiles(journal, null, 1_000, 3, panel -> true, policy);

            policy.set(ValueExposure.FULL, true);
            assertThat(profiles.profile("r1").toString()).contains("alice", LOG);

            policy.set(ValueExposure.MASKED, true);
            RequestJournalProfileDto masked = profiles.profile("r1");
            assertThat(masked.toString()).doesNotContain("alice", "s3cr3t", "hunter2", "abc123");

            policy.set(ValueExposure.METADATA_ONLY, true);
            assertThat(profiles.profile("r1").toString())
                    .doesNotContain("alice", "s3cr3t", "hunter2", "abc123", "refused");
        } finally {
            journal.close();
        }
    }

    @Test
    void sqlWhoseBackslashesReadDifferentlyByDialectShowsNothingAfterItsFirstQuote() {
        for (ValueExposure exposure : new ValueExposure[] {ValueExposure.MASKED, ValueExposure.METADATA_ONLY}) {
            JournalTextExposure rule = new JournalTextExposure(exposure, true);
            assertThat(rule.sql("SELECT 'C:\\', 'sk_live_EXAMPLE'")).doesNotContain("sk_live_EXAMPLE");
            assertThat(rule.sql("select * from t where a = 'it\\'s sk_live_EXAMPLE' and b = 1"))
                    .doesNotContain("sk_live_EXAMPLE");
        }
        assertThat(JournalTextExposure.masked().sql("select * from t where a = 'x' and b = 'y'"))
                .isEqualTo("select * from t where a = ? and b = ?");
    }

    @Test
    void metadataOnlyMasksEveryMatrixParameterValueWhateverItsName() {
        JournalTextExposure metadata = new JournalTextExposure(ValueExposure.METADATA_ONLY, false);

        assertThat(metadata.path("/callback;code=opaqueCredential/x;flag")).isEqualTo("/callback;code=******/x;******");
        assertThat(metadata.reapply(row("REQUEST", "", "/callback;code=opaqueCredential", null))
                        .path())
                .doesNotContain("opaqueCredential");
        assertThat(JournalTextExposure.masked().path("/callback;code=opaque")).isEqualTo("/callback;code=opaque");
    }

    @Test
    void sqlThatCannotBeToldApartFromAValueIsNeverShownMasked() {
        JournalTextExposure masked = JournalTextExposure.masked();

        assertThat(masked.sql("select * from t where body = $$top secret$$")).doesNotContain("top secret");
        assertThat(masked.sql("select * from t where body = $tag$top secret (truncated"))
                .doesNotContain("top secret");
        assertThat(masked.sql("select * from t where body = $$top secret")).doesNotContain("top secret");
        assertThat(masked.sql("select * from t where name = \"alice\"")).doesNotContain("alice");
        assertThat(masked.sql("select * from t where id = $1")).isEqualTo("select * from t where id = $1");
        assertThat(masked.sql(null)).isEmpty();
    }

    @Test
    void aMissingPolicyFailsClosedToMasked() {
        assertThat(JournalTextExposure.of(null)).isEqualTo(JournalTextExposure.masked());
        assertThat(JournalTextExposure.of(new MutablePolicy())).isEqualTo(JournalTextExposure.masked());
        assertThat(new JournalTextExposure(null, false)).isEqualTo(JournalTextExposure.masked());
    }

    @Test
    void aStoredRowIsReMaskedUnderTheLivePolicyAndNeverShownLessMaskedThanMasked() {
        // Rows a build before this rule wrote raw.
        ActivityEntryDto sql = row("SQL", SQL, null, null);
        ActivityEntryDto request = row("REQUEST", "GET " + PATH + " → 200", PATH, "/orders");
        ActivityEntryDto rest = row("REST", "GET api.example.com" + PATH + " → 502", PATH, null);
        ActivityEntryDto log = row("LOG", LOG, null, "java.io.IOException: password=hunter2");

        JournalTextExposure full = new JournalTextExposure(ValueExposure.FULL, true);
        for (JournalTextExposure rule :
                List.of(full, new JournalTextExposure(ValueExposure.MASKED, false), JournalTextExposure.masked())) {
            assertThat(rule.reapply(sql).summary()).doesNotContain("alice", "s3cr3t");
            assertThat(rule.reapply(request).summary()).doesNotContain("abc123").startsWith("GET /orders;token=");
            assertThat(rule.reapply(request).path()).doesNotContain("abc123");
            assertThat(rule.reapply(rest).summary()).doesNotContain("abc123").contains("api.example.com");
            assertThat(rule.reapply(log).summary()).doesNotContain("hunter2");
        }

        JournalTextExposure metadata = new JournalTextExposure(ValueExposure.METADATA_ONLY, true);
        for (ActivityEntryDto stored : List.of(sql, request, rest, log)) {
            ActivityEntryDto shown = metadata.reapply(stored);
            assertThat(shown.summary()).isEmpty();
            assertThat(shown.detail()).isNull();
            assertThat(shown.type()).isEqualTo(stored.type());
            assertThat(String.valueOf(shown.path())).doesNotContain("abc123");
        }
        assertThat(full.reapply(null)).isNull();
    }

    private List<ActivityEntryDto> render() {
        return feed.render(entries, EVENT_ID, "run", Filter.NONE, 0).entries();
    }

    private static ActivityEntryDto only(List<ActivityEntryDto> rows, String type) {
        return rows.stream()
                .filter(row -> row.type().equals(type))
                .reduce((a, b) -> {
                    throw new AssertionError("more than one " + type);
                })
                .orElseThrow();
    }

    private static ActivityEntryDto row(String type, String summary, String path, String detail) {
        return new ActivityEntryDto(
                "id", type, 0, "OK", summary, detail, 1L, null, "GET", path, 200, null, false, null, null, false,
                List.of());
    }

    private void record(String requestId, JournalSource source, RuntimeEventPayload payload, long epochMillis) {
        RuntimeEvent event = RuntimeEvent.of(
                source,
                epochMillis,
                1_000_000,
                CorrelationContext.forRequest(requestId),
                "http-1",
                null,
                false,
                payload);
        entries.add(new JournalEntry(entries.size() + 1, event, event.estimatedBytes()));
    }

    private static RuntimeJournal journalOf(List<JournalEntry> entries) {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
        for (JournalEntry entry : entries) {
            journal.offer(entry.event());
        }
        journal.dispatchPending();
        return journal;
    }

    private static final class MutablePolicy implements ExposurePolicy {

        private volatile ValueExposure exposure;
        private volatile boolean maskSecrets = true;

        void set(ValueExposure exposure, boolean maskSecrets) {
            this.exposure = exposure;
            this.maskSecrets = maskSecrets;
        }

        @Override
        public ValueExposure valueExposure() {
            return exposure;
        }

        @Override
        public boolean maskSecrets() {
            return maskSecrets;
        }
    }
}
