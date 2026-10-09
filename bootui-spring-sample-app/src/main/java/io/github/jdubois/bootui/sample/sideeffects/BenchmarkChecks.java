package io.github.jdubois.bootui.sample.sideeffects;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import org.springframework.stereotype.Component;

/**
 * The agent overhead benchmark's JDK checks ({@code docs/PLAN-v2.md} §5.16, M5-6b2): per request, the hooks' fast
 * paths (a SHA-256 digest, an {@code AES/GCM/NoPadding} cipher, and a read through a stream with a filter) and one weak
 * path (an MD5 the application asks for), so the security-sinks sensor's JDK checks are measured where they run.
 */
@Component
public class BenchmarkChecks {

    private static final ObjectInputFilter FILTER =
            ObjectInputFilter.Config.createFilter("maxdepth=5;maxarray=1000;maxrefs=100");

    private final byte[] serialized = serialize(new ArrayList<>(List.of(1, 2, 3)));

    private final byte[] input = "bootui-benchmark".getBytes(StandardCharsets.UTF_8);

    /** The checks of one request; returns the digests' first bytes so nothing is optimized away. */
    public int touch() {
        try {
            int result = MessageDigest.getInstance("SHA-256").digest(input)[0];
            result += MessageDigest.getInstance("MD5").digest(input)[0];
            Cipher.getInstance("AES/GCM/NoPadding");
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
                in.setObjectInputFilter(FILTER);
                result += ((List<?>) in.readObject()).size();
            }
            return result;
        } catch (GeneralSecurityException | IOException | ClassNotFoundException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] serialize(Object value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }
}
