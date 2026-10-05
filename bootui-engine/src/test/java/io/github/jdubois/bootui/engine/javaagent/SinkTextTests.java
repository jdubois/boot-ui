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
