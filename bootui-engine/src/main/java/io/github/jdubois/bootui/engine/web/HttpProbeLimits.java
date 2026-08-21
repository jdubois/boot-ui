package io.github.jdubois.bootui.engine.web;

/**
 * Explicit inbound and outbound budgets for the HTTP Probe panel.
 *
 * <p>BootUI is fail-closed and does bounded work: a probe is a hand-written local request, so every
 * field it carries has a small, predictable ceiling. Without these budgets the only cap in the flow
 * was on the <em>response</em> body, so an oversized request body, path or header collection was bound
 * by the adapter and then forwarded to the local target, doubling the memory cost and pushing
 * unbounded work onto the application under test.
 *
 * <p>All inbound sizes are measured in UTF-8 bytes rather than {@code String} length, so a multi-byte
 * (non-ASCII) payload cannot smuggle several times the byte budget past a character-count check. The
 * counting is capped: it stops as soon as a limit is exceeded and never materialises an encoded copy
 * of the value.
 *
 * <p>The defaults are deliberately generous for real probe use and deliberately far below the request
 * body limits of the underlying stacks (for example the WebFlux codec's 256 KiB in-memory default), so
 * the canonical BootUI validation error — not an opaque framework error — is what a developer sees
 * when a probe is too large.
 *
 * @param maxMethodBytes maximum UTF-8 size of the request method
 * @param maxPathBytes maximum UTF-8 size of the request path
 * @param maxRequestBodyBytes maximum UTF-8 size of the request body
 * @param maxHeaderCount maximum number of request header entries
 * @param maxHeaderNameBytes maximum UTF-8 size of a single request header name
 * @param maxHeaderValueBytes maximum UTF-8 size of a single request header value
 * @param maxTotalHeaderBytes maximum combined UTF-8 size of all request header names and values
 * @param maxResponseBodyBytes maximum response body bytes read back (truncates rather than rejects)
 */
public record HttpProbeLimits(
        int maxMethodBytes,
        int maxPathBytes,
        int maxRequestBodyBytes,
        int maxHeaderCount,
        int maxHeaderNameBytes,
        int maxHeaderValueBytes,
        int maxTotalHeaderBytes,
        int maxResponseBodyBytes) {

    /** Longest accepted request method; every real HTTP method token is far shorter. */
    public static final int DEFAULT_MAX_METHOD_BYTES = 32;

    /** Longest accepted request path, matching the conventional 2 KiB practical URL ceiling. */
    public static final int DEFAULT_MAX_PATH_BYTES = 2048;

    /** Largest accepted request body: generous for a hand-written probe, bounded for the JVM. */
    public static final int DEFAULT_MAX_REQUEST_BODY_BYTES = 64 * 1024; // 64 KiB

    /** Largest accepted number of request headers. */
    public static final int DEFAULT_MAX_HEADER_COUNT = 50;

    /** Largest accepted single request header name. */
    public static final int DEFAULT_MAX_HEADER_NAME_BYTES = 256;

    /** Largest accepted single request header value. */
    public static final int DEFAULT_MAX_HEADER_VALUE_BYTES = 8 * 1024; // 8 KiB

    /** Largest accepted combined size of all request header names and values. */
    public static final int DEFAULT_MAX_TOTAL_HEADER_BYTES = 32 * 1024; // 32 KiB

    public HttpProbeLimits {
        requirePositive(maxMethodBytes, "maxMethodBytes");
        requirePositive(maxPathBytes, "maxPathBytes");
        requirePositive(maxRequestBodyBytes, "maxRequestBodyBytes");
        requirePositive(maxHeaderCount, "maxHeaderCount");
        requirePositive(maxHeaderNameBytes, "maxHeaderNameBytes");
        requirePositive(maxHeaderValueBytes, "maxHeaderValueBytes");
        requirePositive(maxTotalHeaderBytes, "maxTotalHeaderBytes");
        requirePositive(maxResponseBodyBytes, "maxResponseBodyBytes");
    }

    /** The BootUI defaults applied by every adapter. */
    public static HttpProbeLimits defaults() {
        return new HttpProbeLimits(
                DEFAULT_MAX_METHOD_BYTES,
                DEFAULT_MAX_PATH_BYTES,
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                DEFAULT_MAX_HEADER_COUNT,
                DEFAULT_MAX_HEADER_NAME_BYTES,
                DEFAULT_MAX_HEADER_VALUE_BYTES,
                DEFAULT_MAX_TOTAL_HEADER_BYTES,
                BoundedBodyReader.HTTP_PROBE_MAX_BYTES);
    }

    /** The defaults with a different response body budget, used to exercise truncation in tests. */
    public HttpProbeLimits withMaxResponseBodyBytes(int maxResponseBodyBytes) {
        return new HttpProbeLimits(
                maxMethodBytes,
                maxPathBytes,
                maxRequestBodyBytes,
                maxHeaderCount,
                maxHeaderNameBytes,
                maxHeaderValueBytes,
                maxTotalHeaderBytes,
                maxResponseBodyBytes);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
