package io.github.jdubois.bootui.console.activity;

import static io.github.jdubois.bootui.console.activity.ConsoleActivityTestFixtures.entry;
import static io.github.jdubois.bootui.console.activity.ConsoleActivityTestFixtures.entryWithCorrelation;
import static io.github.jdubois.bootui.console.activity.ConsoleActivityTestFixtures.stored;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Tests for {@link ConsoleActivityProfileAssembler}: the console's per-request drill-down builder. Pins
 * the critical UI constraint discovered while designing this class &mdash; {@code
 * LiveActivity.vue}'s profile drawer unconditionally dereferences {@code profile.request.*} with no
 * null-guard, so {@code request} must never be {@code null} when {@code available} is {@code true} —
 * plus the cross-instance correlation behavior ({@code remoteActivity} includes every correlated entry,
 * including the anchor's own, oldest-first, with {@code parentId} cleared).
 */
class ConsoleActivityProfileAssemblerTests {

    /** A store whose behavior is fully scripted per test via the fields below. */
    private static final class ScriptedStore implements ReactiveActivityStore {
        StoredActivityEntry byEntryId;
        List<StoredActivityEntry> byCorrelationId = List.of();
        String lastCorrelationIdQueried;
        int lastLimitQueried = -1;

        @Override
        public Mono<Void> appendBatch(List<StoredActivityEntry> entries) {
            return Mono.empty();
        }

        @Override
        public Mono<ActivityPage> query(ActivityQuery query) {
            return Mono.just(ActivityPage.EMPTY);
        }

        @Override
        public Mono<ActivityPage> queryAllInstances(ActivityQuery query) {
            return Mono.just(ActivityPage.EMPTY);
        }

        @Override
        public Mono<List<StoredActivityEntry>> queryByCorrelationId(String correlationId, int limit) {
            this.lastCorrelationIdQueried = correlationId;
            this.lastLimitQueried = limit;
            return Mono.just(byCorrelationId);
        }

        @Override
        public Mono<StoredActivityEntry> findByEntryId(String entryId) {
            return byEntryId == null ? Mono.empty() : Mono.just(byEntryId);
        }

        @Override
        public Mono<Void> prune(String instanceId, long olderThanEpochMillis) {
            return Mono.empty();
        }
    }

    @Test
    void aBlankEntryIdIsUnavailableWithoutQueryingTheStore() {
        ScriptedStore store = new ScriptedStore();

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, "   "))
                .assertNext(profile -> {
                    assertThat(profile.available()).isFalse();
                    assertThat(profile.request()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void aNullEntryIdIsUnavailableWithoutQueryingTheStore() {
        ScriptedStore store = new ScriptedStore();

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, null))
                .assertNext(profile -> assertThat(profile.available()).isFalse())
                .verifyComplete();
    }

    @Test
    void anUnknownEntryIdIsUnavailableWithAClearReason() {
        ScriptedStore store = new ScriptedStore();
        store.byEntryId = null;

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, "missing"))
                .assertNext(profile -> {
                    assertThat(profile.available()).isFalse();
                    assertThat(profile.request()).isNull();
                    assertThat(profile.unavailableReason()).contains("missing");
                })
                .verifyComplete();
    }

    @Test
    void aFoundRequestEntryIsSynthesizedIntoANonNullHttpExchange() {
        ScriptedStore store = new ScriptedStore();
        ActivityEntryDto anchorEntry = requestEntryWithCorrelation("e1", "GET", "/api/hello", 200, 12, "corr-1");
        store.byEntryId = stored("spring-sample--8080", 1, anchorEntry);
        store.byCorrelationId = List.of(store.byEntryId);

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, "e1"))
                .assertNext(profile -> {
                    assertThat(profile.available()).isTrue();
                    assertThat(profile.request()).isNotNull();
                    assertThat(profile.request().id()).isEqualTo("e1");
                    assertThat(profile.request().method()).isEqualTo("GET");
                    assertThat(profile.request().path()).isEqualTo("/api/hello");
                    assertThat(profile.request().status()).isEqualTo(200);
                    assertThat(profile.request().statusFamily()).isEqualTo("2xx");
                    assertThat(profile.request().durationMs()).isEqualTo(12L);
                    assertThat(profile.request().traceId()).isEqualTo("corr-1");
                })
                .verifyComplete();
    }

    @Test
    void sqlExceptionsAndSecurityAreAlwaysEmptyAndTraceAndTimingAreAlwaysNull() {
        ScriptedStore store = new ScriptedStore();
        ActivityEntryDto anchorEntry = requestEntryWithCorrelation("e1", "GET", "/x", 200, 1, "corr-1");
        store.byEntryId = stored("a", 1, anchorEntry);
        store.byCorrelationId = List.of(store.byEntryId);

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, "e1"))
                .assertNext(profile -> {
                    assertThat(profile.sql()).isEmpty();
                    assertThat(profile.sqlGroups()).isEmpty();
                    assertThat(profile.sqlCorrelationApproximate()).isFalse();
                    assertThat(profile.exceptions()).isEmpty();
                    assertThat(profile.security()).isEmpty();
                    assertThat(profile.trace()).isNull();
                    assertThat(profile.timing()).isNull();
                    assertThat(profile.notes())
                            .anyMatch(note -> note.contains("only available on the originating instance"));
                })
                .verifyComplete();
    }

    @Test
    void everyCorrelatedEntryIncludingTheAnchorsOwnAppearsInRemoteActivity() {
        ScriptedStore store = new ScriptedStore();
        ActivityEntryDto anchorEntry = requestEntryWithCorrelation("e1", "GET", "/x", 200, 10, "corr-1");
        ActivityEntryDto sqlEntry = entryWithCorrelation("e2", "SQL", 2_000, "OK", "SELECT 1", "corr-1");
        StoredActivityEntry anchor = stored("spring-sample--8080", 1, anchorEntry);
        StoredActivityEntry sql = stored("quarkus-sample--8081", 1, sqlEntry);
        store.byEntryId = anchor;
        store.byCorrelationId = List.of(sql, anchor);

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, "e1"))
                .assertNext(profile -> {
                    assertThat(profile.remoteActivity()).hasSize(2);
                    assertThat(profile.remoteActivity())
                            .extracting(remote -> remote.entry().id())
                            .containsExactly("e1", "e2"); // oldest-first: anchor(ts=1000) before sql(ts=2000)
                })
                .verifyComplete();
    }

    @Test
    void remoteActivityEntriesHaveTheirParentIdCleared() {
        ScriptedStore store = new ScriptedStore();
        ActivityEntryDto anchorWithParent = new ActivityEntryDto(
                "e1",
                "REQUEST",
                1_000,
                "OK",
                "GET /x",
                null,
                10L,
                "corr-1",
                "GET",
                "/x",
                200,
                "t1",
                true,
                "some-local-parent-id",
                null,
                false);
        StoredActivityEntry anchor = stored("a", 1, anchorWithParent);
        store.byEntryId = anchor;
        store.byCorrelationId = List.of(anchor);

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, "e1"))
                .assertNext(profile -> assertThat(
                                profile.remoteActivity().get(0).entry().parentId())
                        .isNull())
                .verifyComplete();
    }

    @Test
    void aBlankCorrelationIdProducesASingleEntrySelfProfileWithoutQueryingByCorrelationId() {
        ScriptedStore store = new ScriptedStore();
        ActivityEntryDto anchorEntry = entry("e1", "SECURITY", 1_000, "WARN", "login failed");
        store.byEntryId = stored("spring-sample--8080", 1, anchorEntry);
        // Deliberately leave byCorrelationId non-empty to prove it's never consulted for a blank correlationId.
        store.byCorrelationId =
                List.of(new StoredActivityEntry("other", 99, entry("should-not-appear", "REQUEST", 1, "OK", "x")));

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, "e1"))
                .assertNext(profile -> {
                    assertThat(profile.available()).isTrue();
                    assertThat(profile.remoteActivity()).hasSize(1);
                    assertThat(profile.remoteActivity().get(0).entry().id()).isEqualTo("e1");
                    assertThat(profile.notes()).anyMatch(note -> note.contains("no trace id"));
                })
                .verifyComplete();
    }

    @Test
    void remoteActivityIsCappedAtTheMaximumCorrelatedEntries() {
        ScriptedStore store = new ScriptedStore();
        ActivityEntryDto anchorEntry = requestEntryWithCorrelation("e1", "GET", "/x", 200, 1, "corr-1");
        StoredActivityEntry anchor = stored("a", 1, anchorEntry);
        store.byEntryId = anchor;
        // The assembler passes the cap through to queryByCorrelationId; simulate the store already respecting it.
        List<StoredActivityEntry> capped = new ArrayList<>();
        capped.add(anchor);
        for (int i = 0; i < 99; i++) {
            capped.add(stored("a", i + 2, entryWithCorrelation("extra-" + i, "SQL", 1_000 + i, "OK", "q", "corr-1")));
        }
        store.byCorrelationId = capped;

        StepVerifier.create(ConsoleActivityProfileAssembler.assemble(store, "e1"))
                .assertNext(profile -> assertThat(profile.remoteActivity()).hasSize(100))
                .verifyComplete();

        assertThat(store.lastCorrelationIdQueried).isEqualTo("corr-1");
        assertThat(store.lastLimitQueried).isEqualTo(100);
    }

    private static ActivityEntryDto requestEntryWithCorrelation(
            String id, String method, String path, int status, long durationMs, String correlationId) {
        return new ActivityEntryDto(
                id,
                "REQUEST",
                1_000,
                status >= 400 ? "ERROR" : "OK",
                method + " " + path,
                null,
                durationMs,
                correlationId,
                method,
                path,
                status,
                "http-thread-1",
                true,
                null,
                null,
                false);
    }
}
