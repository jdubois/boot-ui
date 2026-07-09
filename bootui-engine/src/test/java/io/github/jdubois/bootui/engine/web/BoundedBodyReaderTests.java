package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BoundedBodyReader}.
 *
 * <p>Covers exact-limit, over-limit, streaming/chunked (normal InputStream), multibyte UTF-8,
 * empty body, and normal (under-limit) responses for both {@link BoundedBodyReader#readBounded} and
 * {@link BoundedBodyReader#readString}.
 */
class BoundedBodyReaderTests {

    // ── readBounded ───────────────────────────────────────────────────────────

    @Test
    void readBounded_emptyBodyReturnsFalseAndEmptyString() throws IOException {
        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(stream(new byte[0]), 10);
        assertThat(result.body()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void readBounded_bodyUnderLimitNotTruncated() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(stream(data), 10);
        assertThat(result.body()).isEqualTo("hello");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void readBounded_bodyAtExactLimitNotTruncated() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8); // 5 bytes
        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(stream(data), 5);
        assertThat(result.body()).isEqualTo("hello");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void readBounded_bodyOneByteLargerIsTruncated() throws IOException {
        byte[] data = "hello!".getBytes(StandardCharsets.UTF_8); // 6 bytes
        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(stream(data), 5);
        assertThat(result.body()).isEqualTo("hello"); // only first 5 bytes
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void readBounded_largeBodyIsTruncatedToExactLimit() throws IOException {
        byte[] data = new byte[1000];
        Arrays.fill(data, (byte) 'A');
        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(stream(data), 100);
        assertThat(result.body()).hasSize(100);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void readBounded_multibyteUtf8_exactBoundaryNotTruncated() throws IOException {
        // U+00E9 (é) encodes to 2 bytes in UTF-8: 0xC3 0xA9
        String text = "caf\u00e9"; // 4 chars, 5 bytes
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        assertThat(data).hasSize(5);

        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(stream(data), 5);
        assertThat(result.body()).isEqualTo("caf\u00e9");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void readBounded_multibyteUtf8_truncatedInMiddleOfSequence() throws IOException {
        // U+00E9 (é) is 0xC3 0xA9; cut at 4 bytes splits the 2-byte sequence
        String text = "caf\u00e9"; // 4 chars, 5 bytes
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(stream(data), 4);
        // Body must be exactly 4 bytes; the incomplete sequence is replaced by U+FFFD
        assertThat(result.truncated()).isTrue();
        // "caf" (3 bytes) + 0xC3 (start of 2-byte seq) → decoded as "caf" + replacement char
        assertThat(result.body()).startsWith("caf");
    }

    @Test
    void readBounded_streamingChunkedDeliveryHandledCorrectly() throws IOException {
        // Simulate a stream that delivers bytes in small chunks (not all at once)
        byte[] data = "streaming-body".getBytes(StandardCharsets.UTF_8);
        InputStream chunked = new ChunkedInputStream(data, 3); // deliver 3 bytes at a time

        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(chunked, 100);
        assertThat(result.body()).isEqualTo("streaming-body");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void readBounded_streamingChunkedAndOversized() throws IOException {
        byte[] data = new byte[20];
        Arrays.fill(data, (byte) 'X');
        InputStream chunked = new ChunkedInputStream(data, 3);

        BoundedBodyReader.BoundedRead result = BoundedBodyReader.readBounded(chunked, 10);
        assertThat(result.body()).hasSize(10);
        assertThat(result.truncated()).isTrue();
    }

    // ── readString ────────────────────────────────────────────────────────────

    @Test
    void readString_emptyBodyReturnsEmptyString() throws IOException {
        String result = BoundedBodyReader.readString(stream(new byte[0]), 10);
        assertThat(result).isEmpty();
    }

    @Test
    void readString_bodyUnderLimitReturnsString() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        String result = BoundedBodyReader.readString(stream(data), 10);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void readString_bodyAtExactLimitReturnsString() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        String result = BoundedBodyReader.readString(stream(data), 5);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void readString_bodyOneByteLargerThrowsIOException() {
        byte[] data = "hello!".getBytes(StandardCharsets.UTF_8); // 6 bytes
        assertThatIOException()
                .isThrownBy(() -> BoundedBodyReader.readString(stream(data), 5))
                .withMessageContaining("Response body exceeds 5 bytes");
    }

    @Test
    void readString_largeBodyThrowsIOException() {
        byte[] data = new byte[1000];
        Arrays.fill(data, (byte) 'Z');
        assertThatIOException()
                .isThrownBy(() -> BoundedBodyReader.readString(stream(data), 100))
                .withMessageContaining("exceeds 100 bytes");
    }

    @Test
    void readString_multibyteUtf8_atExactLimitReturnsString() throws IOException {
        String text = "caf\u00e9"; // 5 bytes in UTF-8
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        String result = BoundedBodyReader.readString(stream(data), 5);
        assertThat(result).isEqualTo("caf\u00e9");
    }

    @Test
    void readString_multibyteUtf8_oversizedThrowsIOException() {
        String text = "caf\u00e9extra"; // 5 + 5 = 10 bytes
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        assertThatIOException()
                .isThrownBy(() -> BoundedBodyReader.readString(stream(data), 5))
                .withMessageContaining("exceeds 5 bytes");
    }

    @Test
    void readString_streamingChunkedDelivery() throws IOException {
        byte[] data = "streaming-strict".getBytes(StandardCharsets.UTF_8);
        InputStream chunked = new ChunkedInputStream(data, 4);

        String result = BoundedBodyReader.readString(chunked, 100);
        assertThat(result).isEqualTo("streaming-strict");
    }

    @Test
    void readString_streamingChunkedAndOversizedThrows() {
        byte[] data = new byte[30];
        Arrays.fill(data, (byte) 'Y');
        InputStream chunked = new ChunkedInputStream(data, 4);

        assertThatIOException()
                .isThrownBy(() -> BoundedBodyReader.readString(chunked, 10))
                .withMessageContaining("exceeds 10 bytes");
    }

    // ── constants ─────────────────────────────────────────────────────────────

    @Test
    void perClientByteConstantsArePositive() {
        assertThat(BoundedBodyReader.HTTP_PROBE_MAX_BYTES).isPositive();
        assertThat(BoundedBodyReader.PENTESTING_MAX_BYTES).isPositive();
        assertThat(BoundedBodyReader.GRAALVM_METADATA_MAX_BYTES).isPositive();
        assertThat(BoundedBodyReader.OSV_QUERYBATCH_MAX_BYTES).isPositive();
        assertThat(BoundedBodyReader.OSV_ADVISORY_MAX_BYTES).isPositive();
        assertThat(BoundedBodyReader.OSV_EPSS_MAX_BYTES).isPositive();
        assertThat(BoundedBodyReader.GITHUB_MAX_BYTES).isPositive();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static InputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }

    /**
     * An {@link InputStream} that delivers at most {@code chunkSize} bytes per {@code read(byte[],
     * int, int)} call, simulating a chunked/streaming HTTP response body.
     */
    private static final class ChunkedInputStream extends InputStream {

        private final byte[] data;
        private final int chunkSize;
        private int pos = 0;

        ChunkedInputStream(byte[] data, int chunkSize) {
            this.data = data;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            if (pos >= data.length) {
                return -1;
            }
            return data[pos++] & 0xff;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int toRead = Math.min(len, Math.min(chunkSize, data.length - pos));
            System.arraycopy(data, pos, buf, off, toRead);
            pos += toRead;
            return toRead;
        }
    }
}
