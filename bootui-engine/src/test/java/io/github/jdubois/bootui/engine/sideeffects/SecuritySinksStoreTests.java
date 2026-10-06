package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Security-sinks rows in the store ({@code docs/PLAN-v2.md} §5.17, M5-6 design Important 11): a match outside a literal
 * or of digits only is shown once two requests produced different raw texts with the same redacted text; a request
 * repeating the same text never fills the confirmations; and past the row caps an unconfirmed match is never shown
 * through the Other row, whose sentence claims nothing about a literal.
 */
class SecuritySinksStoreTests {

    private static final long NOW = 1_000_000L;
    private static final String SENSOR = SideEffectsCatalog.SECURITY_SINKS_ID;

    @Test
    void repeatedTextsNeverFillTheConfirmationsAndADifferentValueConfirms() {
        SideEffectsStore store = new SideEffectsStore(0L);
        for (int request = 1; request <= 5; request++) {
            store.add(sink(request, "select * from t order by {sort}", SideEffectsCatalog.SINK_OUTSIDE_LITERAL, 11, 7));
        }
        store.resolve(routes(6), NOW);
        assertThat(store.rows(SENSOR, true, true))
                .as("the same raw text five times")
                .isEmpty();
        assertThat(store.unconfirmed()).isEqualTo(1L);
        assertThat(store.keys().keys())
                .as("a match no second request confirmed is no run key either")
                .noneMatch(key -> SENSOR.equals(key.sensor()));

        store.add(sink(6, "select * from t order by {sort}", SideEffectsCatalog.SINK_OUTSIDE_LITERAL, 12, 7));
        store.resolve(routes(6), NOW);
        assertThat(store.keys().keys()).anyMatch(key -> SENSOR.equals(key.sensor()));

        assertThat(store.rows(SENSOR, true, true)).singleElement().satisfies(row -> {
            assertThat(row.detail()).contains("outside a literal").doesNotContain(SinkWording.SEEN_ONCE);
            assertThat(row.count()).isEqualTo(6L);
        });
    }

    @Test
    void anotherLiteralVaryingNeverConfirmsAMatchWhoseRedactedTextDiffers() {
        SideEffectsStore store = new SideEffectsStore(0L);
        // LIMIT {pageSize} OFFSET 0, then OFFSET 20: the raw texts differ, and so do the redacted ones.
        store.add(sink(1, "select * from t limit {pageSize} offset ?", SideEffectsCatalog.SINK_NUMERIC, 21, 31));
        store.add(sink(2, "select * from t limit {pageSize} offset ?", SideEffectsCatalog.SINK_NUMERIC, 22, 32));
        store.resolve(routes(2), NOW);

        assertThat(store.rows(SENSOR, true, true)).isEmpty();
    }

    @Test
    void pastTheCapsAnUnconfirmedMatchIsNotShownAndTheOtherRowClaimsNoLiteral() {
        SideEffectsStore store = new SideEffectsStore(0L, 100, 1, 100);
        store.add(sink(1, "select a from t where x = '{name}'", SideEffectsCatalog.SINK_IN_LITERAL, 1, 2));
        store.add(sink(1, "select b from t order by {sort}", SideEffectsCatalog.SINK_OUTSIDE_LITERAL, 3, 4));
        store.add(sink(1, "select c from t where y = '{name}'", SideEffectsCatalog.SINK_IN_LITERAL, 5, 6));
        store.resolve(routes(1), NOW);

        List<SideEffectsRowDto> rows = store.rows(SENSOR, true, true);
        assertThat(rows).hasSize(2);
        SideEffectsRowDto other = rows.get(1);
        assertThat(other.scope()).isEqualTo(SideEffectsRowDto.OTHER);
        assertThat(other.count()).as("only the match that stands alone").isEqualTo(1L);
        assertThat(other.detail()).isEqualTo(SinkWording.OTHER).doesNotContain("literal");
        assertThat(store.unconfirmed()).isEqualTo(1L);
    }

    @Test
    void aValueInsideTrueFalseOrANumberWaitsAndOneCrossingALiteralStandsAlone() {
        SideEffectsStore store = new SideEffectsStore(0L);
        int bare = SideEffectsCatalog.SINK_IN_LITERAL | SideEffectsCatalog.SINK_BARE_LITERAL;
        store.add(sink(1, "select * from t where active = {on}", bare, 1, 2));
        store.resolve(routes(1), NOW);
        assertThat(store.rows(SENSOR, true, true)).as("true in one request").isEmpty();

        int crossing = SideEffectsCatalog.SINK_OUTSIDE_LITERAL | SideEffectsCatalog.SINK_CROSSES_LITERAL;
        store.add(sink(2, "select * from users where name = {name} and p = ?", crossing, 3, 4));
        store.resolve(routes(2), NOW);
        assertThat(store.rows(SENSOR, true, true)).singleElement().satisfies(row -> {
            assertThat(row.target()).isEqualTo("select * from users where name = {name} and p = ?");
            assertThat(row.detail()).contains("outside a literal").endsWith(SinkWording.SEEN_ONCE);
        });
    }

    @Test
    void aMatchWhoseRedactedTextWasNotKeptIsNeverConfirmed() {
        SideEffectsStore store = new SideEffectsStore(0L);
        store.add(sink(1, SideEffectsService.TEXT_NOT_KEPT, SideEffectsCatalog.SINK_POSITION_UNKNOWN, 1, 0));
        store.add(sink(2, SideEffectsService.TEXT_NOT_KEPT, SideEffectsCatalog.SINK_POSITION_UNKNOWN, 2, 0));
        store.resolve(routes(2), NOW);

        assertThat(store.rows(SENSOR, true, true)).isEmpty();
    }

    private static SideEffectsStore.Observation sink(
            long request, String target, int flags, long rawHash, long redactedHash) {
        SideEffectRecord record = new SideEffectRecord(
                SideEffectsCatalog.RECORD_SECURITY_SINKS,
                SideEffectsCatalog.KIND_SINK_SQL,
                1L,
                NOW,
                NOW,
                request,
                0L,
                0L,
                1,
                flags,
                1,
                0,
                0,
                1,
                1L,
                rawHash,
                redactedHash,
                0,
                0);
        return SideEffectsStore.Observation.sink(
                record,
                SENSOR,
                SideEffectsCatalog.SQL_TEXT,
                target,
                "demo.Repository#find",
                null,
                null,
                (flags & 0x3) == SideEffectsCatalog.SINK_OUTSIDE_LITERAL
                        ? SideEffectsCatalog.OUTSIDE_LITERAL
                        : (flags & 0x3) == SideEffectsCatalog.SINK_POSITION_UNKNOWN
                                ? null
                                : SideEffectsCatalog.INSIDE_LITERAL,
                "param");
    }

    private static Map<String, String> routes(int requests) {
        java.util.LinkedHashMap<String, String> routes = new java.util.LinkedHashMap<>();
        for (int request = 1; request <= requests; request++) {
            routes.put(String.format("%016x", request), "GET /search");
        }
        return routes;
    }
}
