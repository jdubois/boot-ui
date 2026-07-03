package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActivityCursorTests {

    @Test
    void encodeDecodeRoundTrips() {
        ActivityCursor cursor = new ActivityCursor(1_700_000_000_000L, 42L);
        ActivityCursor decoded = ActivityCursor.decode(cursor.encode());
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void decodeReturnsNullForNullOrBlankInput() {
        assertThat(ActivityCursor.decode(null)).isNull();
        assertThat(ActivityCursor.decode("")).isNull();
        assertThat(ActivityCursor.decode("   ")).isNull();
    }

    @Test
    void decodeReturnsNullForMalformedInput() {
        assertThat(ActivityCursor.decode("not-a-cursor")).isNull();
        assertThat(ActivityCursor.decode("123")).isNull();
        assertThat(ActivityCursor.decode("abc_42")).isNull();
        assertThat(ActivityCursor.decode("123_abc")).isNull();
        assertThat(ActivityCursor.decode("123_")).isNull();
        assertThat(ActivityCursor.decode("_42")).isNull();
    }

    @Test
    void isAfterOrdersByTimestampThenSeqDescending() {
        ActivityCursor cursor = new ActivityCursor(100L, 5L);

        // Same timestamp, smaller seq: strictly older in newest-first order -> qualifies for next page.
        assertThat(cursor.isAfter(100L, 4L)).isTrue();
        // Same timestamp, larger or equal seq: not older -> excluded.
        assertThat(cursor.isAfter(100L, 6L)).isFalse();
        assertThat(cursor.isAfter(100L, 5L)).isFalse();
        // Older timestamp altogether -> qualifies regardless of seq.
        assertThat(cursor.isAfter(99L, 999L)).isTrue();
        // Newer timestamp altogether -> excluded regardless of seq.
        assertThat(cursor.isAfter(101L, 0L)).isFalse();
    }
}
