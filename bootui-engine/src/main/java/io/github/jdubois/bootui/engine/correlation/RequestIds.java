package io.github.jdubois.bootui.engine.correlation;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates BootUI's own request and execution ids.
 *
 * <p>An id is 16 lowercase hexadecimal characters from 64 random bits, so ids are unique within a process without
 * coordination between threads, and carry no information about the request. They exist whether or not a tracer is
 * active, which is what lets BootUI correlate identical, overlapping requests without tracing.</p>
 */
public final class RequestIds {

    private static final HexFormat HEX = HexFormat.of();

    private RequestIds() {}

    /** A new id for an inbound request, a scheduled run, or a consumed message. */
    public static String next() {
        return HEX.toHexDigits(ThreadLocalRandom.current().nextLong());
    }
}
