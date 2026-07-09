package io.github.jdubois.bootui.engine.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Shared bounded-reading primitive for all BootUI outbound HTTP integrations.
 *
 * <p>A request timeout bounds <em>duration</em>, not <em>bytes transferred</em> within that duration.
 * A large or streaming response can therefore consume substantial heap even when the timeout fires on
 * schedule. This utility stops reading at a per-client byte budget, so memory use is bounded
 * independently of timeout duration and independently of how fast the remote end sends data.
 *
 * <p>Two read modes are offered:
 * <ol>
 *   <li>{@link #readBounded(InputStream, int, Charset)} — for <em>probe</em> use: reads up to
 *       {@code maxBytes}, returns a {@link BoundedRead} that carries both the (possibly truncated) body
 *       and a {@code truncated} flag. Intentional truncation, not an error.</li>
 *   <li>{@link #readString(InputStream, int, Charset)} — for <em>required JSON</em> use: reads up to
 *       {@code maxBytes + 1} bytes; if the response is larger, throws {@link IOException} with a clear
 *       message so the caller can surface a bounded error status without including response content.
 *       Never silently truncates data that a parser would then accept as valid JSON.</li>
 * </ol>
 *
 * <p>Both methods use {@link InputStream#readNBytes(int)} internally, which reads at most the
 * requested number of bytes and returns as soon as that budget is consumed or the stream ends —
 * it does <em>not</em> buffer the full response first.
 *
 * <p>To use with the JDK {@code HttpClient}, switch {@code BodyHandlers.ofString()} to
 * {@code BodyHandlers.ofInputStream()} and wrap the body in a try-with-resources:
 * <pre>{@code
 * HttpResponse<InputStream> response =
 *         client.send(request, HttpResponse.BodyHandlers.ofInputStream());
 * try (InputStream body = response.body()) {
 *     String text = BoundedBodyReader.readString(body, MAX_BYTES, StandardCharsets.UTF_8);
 *     // parse text as JSON …
 * }
 * }</pre>
 *
 * <h2>Per-client byte budgets</h2>
 * <table>
 *   <tr><th>Client</th><th>Constant</th><th>Value</th><th>Behavior on excess</th></tr>
 *   <tr><td>HTTP Probe</td><td>{@link #HTTP_PROBE_MAX_BYTES}</td><td>1 MiB</td><td>truncate + flag</td></tr>
 *   <tr><td>Pentesting local</td><td>{@link #PENTESTING_MAX_BYTES}</td><td>8 KiB</td><td>truncate (stop early)</td></tr>
 *   <tr><td>GraalVM metadata</td><td>{@link #GRAALVM_METADATA_MAX_BYTES}</td><td>512 KiB</td><td>reject (IOException)</td></tr>
 *   <tr><td>OSV querybatch</td><td>{@link #OSV_QUERYBATCH_MAX_BYTES}</td><td>5 MiB</td><td>reject (IOException)</td></tr>
 *   <tr><td>OSV advisory</td><td>{@link #OSV_ADVISORY_MAX_BYTES}</td><td>1 MiB</td><td>reject (IOException)</td></tr>
 *   <tr><td>OSV EPSS</td><td>{@link #OSV_EPSS_MAX_BYTES}</td><td>1 MiB</td><td>reject (IOException)</td></tr>
 *   <tr><td>GitHub</td><td>{@link #GITHUB_MAX_BYTES}</td><td>2 MiB</td><td>reject (IOException)</td></tr>
 * </table>
 */
public final class BoundedBodyReader {

    // ── per-client byte budgets ───────────────────────────────────────────────

    /** Maximum response body for the HTTP Probe panel (local loopback probe). */
    public static final int HTTP_PROBE_MAX_BYTES = 1 * 1024 * 1024; // 1 MiB

    /**
     * Maximum response body for pentesting synthetic loopback probes. Matches the existing
     * {@code PentestingLocalHttpResponse.MAX_BODY_CHARS} ceiling so reading stops at the byte level
     * before the char-level truncation in that record fires.
     */
    public static final int PENTESTING_MAX_BYTES = 8192; // 8 KiB

    /** Maximum response body for a GraalVM reachability-metadata {@code index.json} fetch. */
    public static final int GRAALVM_METADATA_MAX_BYTES = 512 * 1024; // 512 KiB

    /** Maximum response body for a single OSV {@code /v1/querybatch} response. */
    public static final int OSV_QUERYBATCH_MAX_BYTES = 5 * 1024 * 1024; // 5 MiB

    /** Maximum response body for a single OSV {@code /v1/vulns/{id}} advisory detail response. */
    public static final int OSV_ADVISORY_MAX_BYTES = 1 * 1024 * 1024; // 1 MiB

    /** Maximum response body for a single FIRST.org EPSS {@code /data/v1/epss} response. */
    public static final int OSV_EPSS_MAX_BYTES = 1 * 1024 * 1024; // 1 MiB

    /** Maximum response body for a single GitHub API call. */
    public static final int GITHUB_MAX_BYTES = 2 * 1024 * 1024; // 2 MiB

    private BoundedBodyReader() {}

    // ── truncating mode (probe use) ───────────────────────────────────────────

    /**
     * Reads at most {@code maxBytes} bytes from {@code is}, decodes them with {@code charset}, and
     * returns a {@link BoundedRead} indicating whether the response was truncated.
     *
     * <p>Reading stops as soon as {@code maxBytes + 1} bytes have been consumed from the stream;
     * only the first {@code maxBytes} are decoded. The extra byte is needed to distinguish an exactly
     * maxBytes response from a larger one.
     *
     * <p>UTF-8 decoding of an abruptly truncated byte sequence replaces the final incomplete code
     * point with the Unicode replacement character ({@code U+FFFD}); this is the same behaviour as
     * {@code new String(bytes, charset)}.
     *
     * @param is       the response body stream; {@link InputStream#close()} is the caller's responsibility
     * @param maxBytes maximum number of body bytes to decode; must be positive
     * @param charset  character set to use for byte-to-string decoding
     * @return a {@link BoundedRead} with the decoded body and a truncation flag
     * @throws IOException if reading from the stream fails
     */
    public static BoundedRead readBounded(InputStream is, int maxBytes, Charset charset) throws IOException {
        byte[] buf = is.readNBytes(maxBytes + 1);
        boolean truncated = buf.length > maxBytes;
        byte[] data = truncated ? Arrays.copyOf(buf, maxBytes) : buf;
        return new BoundedRead(new String(data, charset), truncated);
    }

    /**
     * Reads at most {@code maxBytes} bytes from {@code is} and decodes them with {@code charset}.
     * UTF-8 variant.
     */
    public static BoundedRead readBounded(InputStream is, int maxBytes) throws IOException {
        return readBounded(is, maxBytes, StandardCharsets.UTF_8);
    }

    // ── strict mode (external JSON use) ──────────────────────────────────────

    /**
     * Reads the complete response body from {@code is}, up to {@code maxBytes}. If the response
     * contains more than {@code maxBytes} bytes, throws {@link IOException} with a clear diagnostic
     * message — it never silently truncates a response that a downstream JSON parser would then
     * accept as valid.
     *
     * <p>Reading stops as soon as {@code maxBytes + 1} bytes have been consumed from the stream, so
     * memory use is bounded at {@code maxBytes + 1} bytes regardless of the actual response size or
     * how fast the remote end sends data.
     *
     * @param is       the response body stream; {@link InputStream#close()} is the caller's responsibility
     * @param maxBytes maximum number of body bytes to accept; must be positive
     * @param charset  character set to use for byte-to-string decoding
     * @return the decoded body string, never longer than {@code maxBytes} bytes
     * @throws IOException if the response exceeds {@code maxBytes} bytes or if reading fails
     */
    public static String readString(InputStream is, int maxBytes, Charset charset) throws IOException {
        byte[] buf = is.readNBytes(maxBytes + 1);
        if (buf.length > maxBytes) {
            throw new IOException("Response body exceeds " + maxBytes + " bytes");
        }
        return new String(buf, charset);
    }

    /**
     * Reads the complete response body from {@code is}, up to {@code maxBytes}. UTF-8 variant.
     * Throws {@link IOException} if the response exceeds {@code maxBytes} bytes.
     */
    public static String readString(InputStream is, int maxBytes) throws IOException {
        return readString(is, maxBytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns a {@link HttpResponse.BodyHandler} that discards the response body immediately.
     * Useful as a lightweight alternative to {@code BodyHandlers.discarding()} when a reference to
     * this class is already imported.
     */
    public static HttpResponse.BodyHandler<Void> discarding() {
        return HttpResponse.BodyHandlers.discarding();
    }

    // ── result type ───────────────────────────────────────────────────────────

    /**
     * Result of a {@linkplain #readBounded bounded} read: the (possibly truncated) decoded body and
     * a flag indicating whether the response was larger than the configured byte budget.
     */
    public record BoundedRead(String body, boolean truncated) {

        /** Returns {@code true} when the response body was larger than the configured byte budget. */
        public boolean truncated() {
            return truncated;
        }
    }

    // ── unchecked wrapper (for use in lambdas) ────────────────────────────────

    /**
     * Reads at most {@code maxBytes} bytes from {@code is}; throws {@link UncheckedIOException} if
     * reading fails or the response is oversized. Intended for use in lambdas that cannot declare
     * checked exceptions.
     */
    public static String readStringUnchecked(InputStream is, int maxBytes) {
        try {
            return readString(is, maxBytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
