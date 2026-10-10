package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunSummaryBoundsTests {

    @TempDir
    Path directory;

    @Test
    void impossibleStartupCountsAreRejectedBeforeAnyAllocation() {
        for (long count : new long[] {Integer.MAX_VALUE, 1L << 32, Long.MAX_VALUE, -1L}) {
            assertThatThrownBy(() -> RunSummaryCodec.header(startupPrefix(count)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aSmallMalformedBaselineIsIgnoredRatherThanFailingStartup() throws Exception {
        byte[] summary = startupPrefix(Integer.MAX_VALUE);
        Path path = directory.resolve("baseline.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(0x42554246);
            out.writeByte(RunBaselineFile.FORMAT);
            out.writeUTF("test");
            out.writeUTF("test");
            out.writeInt(summary.length);
            out.write(summary);
        }

        RunBaselineFile.Read read = new RunBaselineFile(path, "test", "test").read();

        assertThat(Files.size(path)).isLessThan(100);
        assertThat(read.summary()).isNull();
        assertThat(read.ignoredReason()).contains("not a readable run summary");
    }

    @Test
    void textAndStringTableCountsCannotWrapOrExceedRemainingBytes() throws Exception {
        for (String method : new String[] {"text", "texts", "strings", "edges", "stringMap", "sourceMap"}) {
            for (long count : new long[] {Integer.MAX_VALUE, 1L << 32, Long.MAX_VALUE, -1L}) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                number(bytes, count);
                assertThatThrownBy(() -> invokeReader(method, bytes.toByteArray()))
                        .as("%s count %s", method, count)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void malformedVarintsAndHistogramBucketsAreRejected() throws Exception {
        byte[] overflowing = new byte[] {
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            (byte) 0xff,
            0x02
        };
        assertThatThrownBy(() -> invokeReader("number", overflowing)).isInstanceOf(IllegalArgumentException.class);
        ByteArrayOutputStream histogram = new ByteArrayOutputStream();
        number(histogram, 1);
        number(histogram, 1);
        number(histogram, 1);
        number(histogram, 1);
        number(histogram, Integer.MAX_VALUE);
        number(histogram, 1);
        assertThatThrownBy(() -> invokeReader("histogram", histogram.toByteArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void invokeReader(String method, byte[] bytes) throws Throwable {
        Class<?> type = Class.forName(RunSummaryCodec.class.getName() + "$In");
        Constructor<?> constructor = type.getDeclaredConstructor(byte[].class);
        constructor.setAccessible(true);
        Object reader = constructor.newInstance((Object) bytes);
        Method read = type.getDeclaredMethod(method);
        read.setAccessible(true);
        try {
            read.invoke(reader);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    private static byte[] startupPrefix(long count) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[] {'B', 'U', 'R', 'S', 15, 1, 'r'});
        for (int i = 0; i < 8; i++) {
            number(bytes, 0);
        }
        number(bytes, 1);
        number(bytes, 0);
        number(bytes, count);
        return bytes.toByteArray();
    }

    private static void number(ByteArrayOutputStream bytes, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            bytes.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }
}
