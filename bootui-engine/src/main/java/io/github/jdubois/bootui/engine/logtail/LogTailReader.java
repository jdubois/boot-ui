package io.github.jdubois.bootui.engine.logtail;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.support.MessageExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The one read path behind the Log Tail panel, shared by the Spring MVC, Spring WebFlux, and Quarkus adapters for
 * the recent snapshot and the SSE stream alike. The MCP {@code get_log_tail} tool and the {@code bootui logs tail}
 * command read the snapshot through those adapters, so every surface applies the same rule.
 *
 * <p>{@link LogTailBuffer} keeps lines exactly as captured, so capture cost is unchanged. This reader applies the
 * {@link MessageExposure} rule that Exceptions and Dev Services share at read time: secret-like assignments in a
 * message are masked under the default {@code MASKED} mode, the message is omitted under {@code METADATA_ONLY} while
 * the timestamp, level, logger, and thread remain, and it is verbatim under {@code FULL}. The policy is resolved on
 * every read, so a live exposure change applies to the next snapshot and the next streamed line, including lines
 * captured before the change, without a restart.</p>
 */
public final class LogTailReader {

    /**
     * Captured lines one stream may hold while its client catches up: the full {@link LogTailBuffer#DEFAULT_MAX_LINES}
     * replay plus a bounded live burst. A stream that falls further behind is disconnected rather than buffering
     * without bound, and the client reconnects.
     */
    public static final int MAX_PENDING_LINES = LogTailBuffer.DEFAULT_MAX_LINES * 2;

    private final LogTailBuffer buffer;

    private final ExposurePolicy exposure;

    public LogTailReader(LogTailBuffer buffer, ExposurePolicy exposure) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.exposure = Objects.requireNonNull(exposure, "exposure");
    }

    /** The retained lines, oldest first, under the exposure policy in force now. */
    public List<LogLineDto> recent() {
        MessageExposure rule = MessageExposure.current(exposure);
        List<LogLineDto> captured = buffer.recent();
        List<LogLineDto> lines = new ArrayList<>(captured.size());
        for (LogLineDto line : captured) {
            lines.add(expose(line, rule));
        }
        return lines;
    }

    /**
     * One captured line under the exposure policy in force now. Streams call this for every line, backlog and live
     * alike, at the moment they write it to the client.
     */
    public LogLineDto expose(LogLineDto captured) {
        return expose(captured, MessageExposure.current(exposure));
    }

    /**
     * Subscribes to captured lines with an atomic backlog replay, as {@link LogTailBuffer#subscribeWithReplay} does.
     * The backlog and the lines passed to {@code subscriber} are captured lines, and {@code subscriber} runs on the
     * logging thread: hand each line to a thread from {@link LogTailBuffer#deliveryThreadFactory(String)} and pass it
     * through {@link #expose(LogLineDto)} there, when writing it, so the exposure work stays off the logging thread,
     * reflects the policy at delivery time, and cannot feed its own log output back into the stream.
     */
    public LogTailBuffer.Subscription subscribeWithReplay(Consumer<LogLineDto> subscriber) {
        return buffer.subscribeWithReplay(subscriber);
    }

    private static LogLineDto expose(LogLineDto line, MessageExposure rule) {
        String message = rule.apply(line.message());
        boolean omitted = rule.omitsText();
        if (Objects.equals(message, line.message()) && omitted == line.messageOmitted()) {
            return line;
        }
        return new LogLineDto(line.timestamp(), line.level(), line.logger(), message, line.thread(), omitted);
    }
}
