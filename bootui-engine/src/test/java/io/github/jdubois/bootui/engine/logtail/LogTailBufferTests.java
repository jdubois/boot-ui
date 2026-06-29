package io.github.jdubois.bootui.engine.logtail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogTailBufferTests {

    private static LogLineDto line(String message) {
        return new LogLineDto(0L, "INFO", "test", message, "main");
    }

    @Test
    void evictsOldestPastLineCap() {
        LogTailBuffer buffer = new LogTailBuffer(3, Long.MAX_VALUE);
        for (int i = 0; i < 5; i++) {
            buffer.add(line("m" + i));
        }
        assertThat(buffer.recent().stream().map(LogLineDto::message)).containsExactly("m2", "m3", "m4");
    }

    @Test
    void evictsOldestPastByteCapButKeepsAtLeastOne() {
        LogTailBuffer buffer = new LogTailBuffer(500, 10);
        buffer.add(line("a")); // 1 byte msg + 4 bytes thread = 5
        buffer.add(line("b"));
        buffer.add(line("c"));
        // total would be 15 bytes; cap 10 evicts the oldest, keeping the newest two.
        assertThat(buffer.recent().stream().map(LogLineDto::message)).containsExactly("b", "c");

        buffer.add(line("x".repeat(100))); // single oversized line: still kept (>=1 retained)
        assertThat(buffer.recent()).hasSize(1);
    }

    @Test
    void subscribeWithReplayReturnsBacklogAndDeliversLiveWithoutGap() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("backlog1"));
        buffer.add(line("backlog2"));

        List<String> live = new ArrayList<>();
        LogTailBuffer.Subscription sub = buffer.subscribeWithReplay(l -> live.add(l.message()));
        assertThat(sub.backlog().stream().map(LogLineDto::message)).containsExactly("backlog1", "backlog2");

        buffer.add(line("live1"));
        assertThat(live).containsExactly("live1");

        sub.unsubscribe().run();
        buffer.add(line("live2"));
        assertThat(live).containsExactly("live1");
    }

    @Test
    void reentrantAppendFromSubscriberIsDroppedNotRecursive() {
        LogTailBuffer buffer = new LogTailBuffer();
        List<String> seen = new ArrayList<>();
        buffer.subscribeWithReplay(l -> {
            seen.add(l.message());
            if (seen.size() == 1) {
                buffer.add(line("reentrant"));
            }
        });
        buffer.add(line("first"));
        assertThat(seen).containsExactly("first");
        assertThat(buffer.recent().stream().map(LogLineDto::message)).containsExactly("first");
    }
}
