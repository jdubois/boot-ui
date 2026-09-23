package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;

class ZipDirectoryTests {

    @Test
    void listsEntryNamesFromTheCentralDirectoryWithoutReadingEntryContents() throws Exception {
        byte[] archive = jar(4 * 1024 * 1024);
        AtomicLong read = new AtomicLong();
        ZipDirectory.Source source = offset -> {
            InputStream input = new ByteArrayInputStream(archive, (int) offset, archive.length - (int) offset) {
                @Override
                public synchronized int read(byte[] b, int off, int len) {
                    int n = super.read(b, off, len);
                    read.addAndGet(Math.max(0, n));
                    return n;
                }
            };
            return input;
        };

        var entries = ZipDirectory.read(source, archive.length);

        assertThat(entries)
                .extracting(ZipDirectory.Entry::name)
                .containsExactly("META-INF/MANIFEST.MF", "com/boosting/big.bin", "com/boosting/App.class", "é.txt");
        // The 4 MiB incompressible entry is neither read nor inflated to list names: only the tail is read.
        assertThat(archive.length).isGreaterThan(4 * 1024 * 1024);
        assertThat(read.get()).isLessThan(128 * 1024);
        Manifest manifest =
                new Manifest(new ByteArrayInputStream(ZipDirectory.readEntry(source, entries.get(0), 1024)));
        assertThat(manifest.getMainAttributes().getValue("Implementation-Title"))
                .isEqualTo("Demo");
    }

    @Test
    void refusesEntriesLargerThanTheCallerBound() throws Exception {
        byte[] archive = jar(100_000);
        ZipDirectory.Source source = source(archive);
        ZipDirectory.Entry big = ZipDirectory.read(source, archive.length).get(1);

        assertThatThrownBy(() -> ZipDirectory.readEntry(source, big, 1024)).isInstanceOf(IOException.class);
        assertThat(ZipDirectory.readEntry(source, big, 200_000)).hasSize(100_000);
    }

    @Test
    void rejectsArchivesThatDoNotValidate() throws Exception {
        byte[] garbage = "not a zip archive, just some bytes long enough".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ZipDirectory.read(source(garbage), garbage.length))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ZipDirectory.read(source(new byte[3]), 3)).isInstanceOf(IOException.class);

        byte[] archive = jar(10);
        byte[] truncated = Arrays.copyOfRange(archive, 40, archive.length);
        assertThatThrownBy(() -> ZipDirectory.read(source(truncated), truncated.length))
                .isInstanceOf(IOException.class);
    }

    @Test
    void ignoresAnEndRecordForgedInsideTheArchiveComment() throws Exception {
        // A fake end record claiming one entry at offset 0, followed by bytes, so its own comment does not end the
        // archive: readers that take the last signature would list a forged directory instead of the real one.
        String forged = "PK\u0005\u0006\u0000\u0000\u0000\u0000\u0001\u0000\u0001\u0000"
                + "\u002e\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000trailer";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new ZipEntry("org/vendor/Library.class"));
            jar.closeEntry();
            jar.setComment(forged);
        }
        byte[] archive = bytes.toByteArray();

        assertThat(ZipDirectory.read(source(archive), archive.length))
                .extracting(ZipDirectory.Entry::name)
                .containsExactly("org/vendor/Library.class");
    }

    private static ZipDirectory.Source source(byte[] archive) {
        return offset -> new ByteArrayInputStream(archive, (int) offset, archive.length - (int) offset);
    }

    private static byte[] jar(int bigEntrySize) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Title", "Demo");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new ZipEntry("com/boosting/big.bin"));
            byte[] content = new byte[bigEntrySize];
            new java.util.Random(42).nextBytes(content);
            jar.write(content);
            jar.closeEntry();
            jar.putNextEntry(new ZipEntry("com/boosting/App.class"));
            jar.closeEntry();
            jar.putNextEntry(new ZipEntry("é.txt"));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
