package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

/** Rewrites an encoded run summary as an earlier codec version wrote it, for the codec's compatibility tests. */
final class LegacyRunSummaries {

    private LegacyRunSummaries() {}

    /**
     * {@code encoded}, a summary with no run start and no application, as {@code version}, before 13, wrote it: the
     * same bytes without the header's absent application, with that version. Later layout differences are the caller's.
     */
    static byte[] asVersion(byte[] encoded, int version) {
        assertThat(version).isLessThan(13);
        int position = 5;
        position = skipNumber(encoded, position, true);
        for (int i = 0; i < 8; i++) {
            position = skipNumber(encoded, position, false);
        }
        assertThat(encoded[position]).as("no run start").isZero();
        int application = position + 1;
        assertThat(encoded[application]).as("no application").isZero();
        byte[] old = new byte[encoded.length - 1];
        System.arraycopy(encoded, 0, old, 0, application);
        System.arraycopy(encoded, application + 1, old, application, encoded.length - application - 1);
        old[4] = (byte) version;
        return old;
    }

    /** Skips an unsigned LEB128 number, and as many bytes as it counts when it is a text's length. */
    private static int skipNumber(byte[] bytes, int position, boolean text) {
        long value = 0;
        int shift = 0;
        byte current;
        do {
            current = bytes[position++];
            value |= (long) (current & 0x7F) << shift;
            shift += 7;
        } while ((current & 0x80) != 0);
        return text ? position + (int) value : position;
    }
}
