package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The code-paths stamp on SQL, REST client, cache, and AI payloads ({@code docs/PLAN-v2.md} §5.14, M5-4c): the earlier
 * constructors leave it 0, interning and AI publication keep it, and its eight bytes are counted.
 */
class CodePathStampPayloadTests {

    private static final long STAMP = 0x1234_5678L;

    @Test
    void theEarlierConstructorsLeaveTheStampUnknown() {
        assertThat(new SqlPayload("select 1", null, "db", false).codePathStamp())
                .isZero();
        assertThat(new SqlPayload("select 1", null, "db", false, null, null, 5L).codePathStamp())
                .isZero();
        assertThat(new RestClientPayload("GET", "h:1", "/", 200, "RestClient", false).codePathStamp())
                .isZero();
        assertThat(new RestClientPayload("GET", "h:1", "/", 200, "RestClient", false, null, 5L).codePathStamp())
                .isZero();
        assertThat(new CachePayload("c", "HIT").codePathStamp()).isZero();
        assertThat(new CachePayload("c", "HIT", null).codePathStamp()).isZero();
        assertThat(new AiPayload("chat", "openai", "gpt", 1L, 2L, "stop", false).codePathStamp())
                .isZero();
        assertThat(new AiPayload("chat", "openai", "gpt", 1L, 2L, "stop", false, "s1", 5L).codePathStamp())
                .isZero();
    }

    @Test
    void interningKeepsTheStampAndItsBytesAreCounted() {
        JournalDictionary dictionary = new JournalDictionary(100, 100_000);
        SqlPayload sql = new SqlPayload("select 1", "A.b:1", "db", false, null, null, 5L, STAMP);
        RestClientPayload rest = new RestClientPayload("GET", "h:1", "/", 200, "RestClient", false, null, 5L, STAMP);
        CachePayload cache = new CachePayload("c", "HIT", null, STAMP);
        AiPayload ai = new AiPayload("chat", "openai", "gpt", 1L, 2L, "stop", false, "s1", 5L, STAMP);

        assertThat(((SqlPayload) sql.interned(dictionary)).codePathStamp()).isEqualTo(STAMP);
        assertThat(((RestClientPayload) rest.interned(dictionary)).codePathStamp())
                .isEqualTo(STAMP);
        assertThat(((CachePayload) cache.interned(dictionary)).codePathStamp()).isEqualTo(STAMP);
        assertThat(((AiPayload) ai.interned(dictionary)).codePathStamp()).isEqualTo(STAMP);
        assertThat(ai.withCodePathStamp(0L).codePathStamp()).isZero();
        assertThat(ai.withCodePathStamp(STAMP)).isSameAs(ai);

        // Fixed parts, with every string counted as the payload's own: the stamp's long is in them.
        assertThat(sql.estimatedBytes())
                .isEqualTo(40
                        + JournalDictionary.retained(null, "select 1")
                        + JournalDictionary.retained(null, "A.b:1")
                        + JournalDictionary.retained(null, "db"));
        assertThat(rest.estimatedBytes())
                .isEqualTo(40
                        + JournalDictionary.retained(null, "GET")
                        + JournalDictionary.retained(null, "h:1")
                        + JournalDictionary.retained(null, "/")
                        + JournalDictionary.retained(null, "RestClient"));
        assertThat(cache.estimatedBytes())
                .isEqualTo(24 + JournalDictionary.retained(null, "c") + JournalDictionary.retained(null, "HIT"));
        assertThat(ai.estimatedBytes())
                .isEqualTo(64
                        + JournalDictionary.retained(null, "chat")
                        + JournalDictionary.retained(null, "openai")
                        + JournalDictionary.retained(null, "gpt")
                        + JournalDictionary.retained(null, "stop")
                        + RuntimeEvent.stringBytes("s1"));
    }

    @Test
    void publishingAnAiCallWithItsSpanKeepsItsStamp() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()),
                RunIdentity.start());
        try {
            AiCallEvents.publish(
                    journal,
                    CorrelationContext.forRequest("r1"),
                    "trace-stamp",
                    "span-stamp",
                    1_000L,
                    5L,
                    9L,
                    "http-1",
                    new AiPayload("chat", "openai", "gpt", 1L, 2L, "stop", false, null, -1, STAMP));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

            AiPayload recorded = (AiPayload) journal.entries().get(0).event().payload();
            assertThat(recorded.spanId()).isEqualTo("span-stamp");
            assertThat(recorded.codePathStamp()).isEqualTo(STAMP);
        } finally {
            journal.close();
        }
    }
}
