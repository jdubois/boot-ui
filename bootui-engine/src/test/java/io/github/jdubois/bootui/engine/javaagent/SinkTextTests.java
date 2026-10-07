package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * The redacted targets of the engine-side sinks ({@code docs/PLAN-v2.md} §5.16, M5-6b): SQL with every literal SQL
 * Trace's lexer sees masked but the matched value's place named, and whether the value sat inside a literal; URLs from
 * their components, ids templated, query keys only, never user information or a value of another parameter.
 */
class SinkTextTests {

    @Test
    void literalsAreMaskedAndAMatchedValueIsNamedInsideOrOutsideALiteral() {
        String sql = "SELECT *  FROM users\n WHERE name = 'x alice y' AND id = 42 ORDER BY created_at -- alice\n";
        int[] spans = spans(sql, "alice", 0, "created_at", 1);
        int[] positions = new int[AgentRequestValues.MAX_VALUES];

        String masked = SqlSinkText.mask(sql, spans, new String[] {"name", "sort"}, positions);

        assertThat(masked).isEqualTo("SELECT * FROM users WHERE name = '…{name}…' AND id = ? ORDER BY {sort}");
        assertThat(positions[0]).isEqualTo(AgentRequestValues.POSITION_IN_LITERAL);
        assertThat(positions[1]).isEqualTo(AgentRequestValues.POSITION_OUTSIDE_LITERAL);
    }

    @Test
    void everyLiteralSqlTraceMasksIsMaskedNeverCopied() {
        String sql = "select * from t where a = \"12 secret1\" and b = 'O\\'Brien secret2' and c = $$secret3$$"
                + " and d = $tag$secret4$tag$ and e = E'secret5' and f = X'0A1B' and g = 1e10 and h = 'it''s secret6'"
                + " and k = 'alice'";
        int[] spans = spans(sql, "alice", 0);
        int[] positions = new int[AgentRequestValues.MAX_VALUES];

        String masked = SqlSinkText.mask(sql, spans, new String[] {"name"}, positions);

        assertThat(masked)
                .doesNotContain("secret")
                .doesNotContain("Brien")
                .doesNotContain("0A1B")
                .doesNotContain("1e10")
                .endsWith("and k = '{name}'");
        assertThat(positions[0]).isEqualTo(AgentRequestValues.POSITION_IN_LITERAL);
    }

    @Test
    void numbersInIdentifiersAreNotLiteralsAndAMatchedNumberIsNamed() {
        String sql = "select col2 from t2 where note = 'it''s 1234' and v = 1234";
        int[] spans = spans(sql, "1234", 0);
        int[] positions = new int[AgentRequestValues.MAX_VALUES];

        String masked = SqlSinkText.mask(sql, spans, new String[] {"n"}, positions);

        assertThat(masked).isEqualTo("select col2 from t2 where note = '…{n}' and v = {n}");
        assertThat(positions[0]).isEqualTo(AgentRequestValues.POSITION_IN_LITERAL);
    }

    @Test
    void aValueClosingAStringLiteralKeepsEveryLaterLiteralMasked() {
        String sql = "select * from users where name = 'x' OR '1'='1' and p = 'secret'";
        int[] spans = spans(sql, "x' OR '1'='1", 0);
        int[] positions = new int[AgentRequestValues.MAX_VALUES];

        String masked = SqlSinkText.mask(sql, spans, new String[] {"name"}, positions);

        assertThat(masked).isEqualTo("select * from users where name = {name} and p = ?");
        assertThat(positions[0])
                .isEqualTo(AgentRequestValues.POSITION_OUTSIDE_LITERAL | AgentRequestValues.FLAG_CROSSES_LITERAL);
    }

    @Test
    void aValueRunningPastANumberKeepsEveryLaterLiteralMasked() {
        String sql = "select * from t where id = 5 OR 1=1 and t='acme'";
        int[] spans = spans(sql, "5 OR 1=1", 0);
        int[] positions = new int[AgentRequestValues.MAX_VALUES];

        String masked = SqlSinkText.mask(sql, spans, new String[] {"id"}, positions);

        assertThat(masked).isEqualTo("select * from t where id = {id} and t=?");
        assertThat(positions[0])
                .isEqualTo(AgentRequestValues.POSITION_OUTSIDE_LITERAL | AgentRequestValues.FLAG_CROSSES_LITERAL);
    }

    @Test
    void aValueInsideANumberOrTrueOrFalseIsABareLiteral() {
        String sql = "select * from t where active = true and v = 4242";
        int[] positions = new int[AgentRequestValues.MAX_VALUES];

        String masked = SqlSinkText.mask(sql, spans(sql, "true", 0, "4242", 1), new String[] {"on", "n"}, positions);

        assertThat(masked).isEqualTo("select * from t where active = {on} and v = {n}");
        assertThat(positions[0])
                .isEqualTo(AgentRequestValues.POSITION_IN_LITERAL | AgentRequestValues.FLAG_BARE_LITERAL);
        assertThat(positions[1])
                .isEqualTo(AgentRequestValues.POSITION_IN_LITERAL | AgentRequestValues.FLAG_BARE_LITERAL);
    }

    @Test
    void aValueInsideAnotherValuesSpanIsUnknownSoItsRowWaitsForConfirmation() {
        String sql = "select * from orders order by created_at";
        int[] positions = new int[AgentRequestValues.MAX_VALUES];

        String masked = SqlSinkText.mask(
                sql, spans(sql, "created_at", 0, "created", 1), new String[] {"sort", "field"}, positions);

        assertThat(masked).isEqualTo("select * from orders order by {sort}");
        assertThat(positions[0]).isEqualTo(AgentRequestValues.POSITION_OUTSIDE_LITERAL);
        assertThat(positions[1]).isEqualTo(AgentRequestValues.POSITION_UNKNOWN);
    }

    @Test
    void aSignedNumberIsABareLiteralThatWaitsForConfirmation() {
        String sql = "select * from places where lat = -33.8688 and lon = 151.2093 and name = 'sydney'";
        int[] positions = new int[AgentRequestValues.MAX_VALUES];

        String masked =
                SqlSinkText.mask(sql, spans(sql, "-33.8688", 0, "151.2093", 1), new String[] {"lat", "lon"}, positions);

        assertThat(masked).isEqualTo("select * from places where lat = {lat} and lon = {lon} and name = ?");
        assertThat(positions[0])
                .isEqualTo(AgentRequestValues.POSITION_OUTSIDE_LITERAL
                        | AgentRequestValues.FLAG_CROSSES_LITERAL
                        | AgentRequestValues.FLAG_BARE_LITERAL);
        assertThat(positions[1])
                .isEqualTo(AgentRequestValues.POSITION_IN_LITERAL | AgentRequestValues.FLAG_BARE_LITERAL);
        assertThat(RequestInputSinks.numeric(sql, spans(sql, "-33.8688", 0), 0))
                .isEqualTo(AgentRequestValues.FLAG_NUMERIC);
        assertThat(RequestInputSinks.numeric("a=-x.1", spans("a=-x.1", "-x.1", 0), 0))
                .isZero();
    }

    @Test
    void noSpanPairOfTwoValuesEverCopiesACharacterOfALiteralOrAComment() {
        String sql = "select * from t where a = 'zq' and b = 7 /* q */ and c = 'qz'";
        for (int from = 0; from < sql.length(); from += 3) {
            for (int to = from + 1; to <= sql.length(); to += 3) {
                for (int other = 0; other < sql.length(); other += 5) {
                    int[] spans = new int[AgentRequestValues.SPANS_LENGTH];
                    spans[AgentRequestValues.S_COUNT] = 2;
                    spans[AgentRequestValues.S_FIRST + 1] = from;
                    spans[AgentRequestValues.S_FIRST + 2] = to;
                    spans[AgentRequestValues.S_FIRST + 3] = 1;
                    spans[AgentRequestValues.S_FIRST + 4] = other;
                    spans[AgentRequestValues.S_FIRST + 5] = Math.min(sql.length(), other + 6);

                    String masked = SqlSinkText.mask(
                            sql, spans, new String[] {"v", "w"}, new int[AgentRequestValues.MAX_VALUES]);

                    assertThat(masked.replace("{v}", "").replace("{w}", ""))
                            .as("spans %d-%d and %d", from, to, other)
                            .doesNotContain("q")
                            .doesNotContain("z")
                            .doesNotContain("7");
                }
            }
        }
    }

    @Test
    void noSpanEverCopiesACharacterOfALiteralOrAComment() {
        // q, z, 7, and 9 appear only inside literals and comments ("zq xq" reads as no column name): no span, wherever
        // it
        // starts or ends, keeps one.
        String sql =
                "select * from t where a = 'zqxzqx' and b = 'zq''w' or c = 7 and d = true /* zqz */ and e = \"zq xq\""
                        + " and f = $$zz$$ and g = 9.9 -- qq\n and h = 'end'";
        for (int from = 0; from < sql.length(); from++) {
            for (int to = from + 1; to <= sql.length(); to++) {
                int[] spans = new int[AgentRequestValues.SPANS_LENGTH];
                spans[AgentRequestValues.S_COUNT] = 1;
                spans[AgentRequestValues.S_FIRST + 1] = from;
                spans[AgentRequestValues.S_FIRST + 2] = to;

                String masked =
                        SqlSinkText.mask(sql, spans, new String[] {"v"}, new int[AgentRequestValues.MAX_VALUES]);

                assertThat(masked.replace("{v}", ""))
                        .as("span %d-%d", from, to)
                        .doesNotContain("q")
                        .doesNotContain("z")
                        .doesNotContain("7")
                        .doesNotContain("9");
            }
        }
    }

    @Test
    void urlsAreTheirOriginTheRedactedPathAndTheQueryKeysOnly() {
        URI uri = URI.create(
                "https://user:p%2Fss@api.example.com:8443/files/alice/42/x%3Fy?token=abc%26z%3Dsecret&q=alice#frag");
        RequestInputSinks.Url url = RequestInputSinks.Url.of(uri);
        int[] spans = spans(url.text, "alice", 0);

        String target = url.target(spans, new String[] {"user"});

        assertThat(target).isEqualTo("https://api.example.com:8443/files/{user}/{id}/x?y?token&q");
        assertThat(url.text).doesNotContain("p/ss").doesNotContain("frag");
    }

    @Test
    void aValueInTheHostKeepsNoUrlTarget() {
        RequestInputSinks.Url url = RequestInputSinks.Url.of(URI.create("http://alice.example.com/x"));
        assertThat(url.target(spans(url.text, "alice", 0), new String[] {"tenant"}))
                .isNull();
    }

    @Test
    void redactionReplacesEverySpanByItsNameAndFailsClosedAcrossABound() {
        String text = "http://h/a/alice/b/alice";
        int[] spans = spans(text, "alice", 0);
        assertThat(RequestInputSinks.redact(text, spans, new String[] {"user"}))
                .isEqualTo("http://h/a/{user}/b/{user}");
        assertThat(RequestInputSinks.redact(text, 0, 13, spans, new String[] {"user"}))
                .as("a span crossing the bound")
                .isNull();
        assertThat(RequestInputSinks.numeric("id=4242", spans("id=4242", "4242", 0), 0))
                .isEqualTo(AgentRequestValues.FLAG_NUMERIC);
        assertThat(RequestInputSinks.reported(spans, 0)).isTrue();
        assertThat(RequestInputSinks.reported(spans, 1)).isFalse();
    }

    /** The spans {@code RequestValues.match} would report for each {@code (value, index)} pair. */
    private static int[] spans(String text, Object... pairs) {
        int[] spans = new int[AgentRequestValues.SPANS_LENGTH];
        int written = 0;
        for (int p = 0; p < pairs.length; p += 2) {
            String value = (String) pairs[p];
            int index = (Integer) pairs[p + 1];
            int from = 0;
            int at;
            while ((at = text.indexOf(value, from)) >= 0 && written < AgentRequestValues.MAX_SPANS) {
                int slot = AgentRequestValues.S_FIRST + 3 * written;
                spans[slot] = index;
                spans[slot + 1] = at;
                spans[slot + 2] = at + value.length();
                written++;
                from = at + value.length();
            }
        }
        spans[AgentRequestValues.S_COUNT] = written;
        return spans;
    }
}
