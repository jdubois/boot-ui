package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks headers only for application class resources encountered by one explicit import. ArchUnit can
 * swallow reader failures and return a non-empty partial result, so failures are retained until the import
 * finishes instead of throwing from its import option.
 *
 * <p>This does not certify semantic import completeness: a valid header does not prove that the remaining
 * class file is valid, that every dependency resolved, or that classpath discovery found every resource.
 */
final class ClassFileImportCheck implements ImportOption {

    private static final int HEADER_BYTES = 8;
    private static final int MAX_REPORTED_VERSIONS = 4;

    private final Map<Failure, Long> failures = new EnumMap<>(Failure.class);
    private final Set<Integer> unsupportedVersions = new LinkedHashSet<>();
    private final byte[] versionProbe;
    private long checked;

    ClassFileImportCheck() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "BootUiHeaderProbe", null, "java/lang/Object", null);
        versionProbe = writer.toByteArray();
    }

    @Override
    public boolean includes(Location location) {
        checked++;
        try {
            URLConnection connection = location.asURI().toURL().openConnection();
            connection.setUseCaches(false);
            byte[] header;
            try (InputStream input = connection.getInputStream()) {
                header = input.readNBytes(HEADER_BYTES);
            }
            if (header.length != HEADER_BYTES) {
                return reject(Failure.TRUNCATED_HEADER);
            }
            if (header[0] != (byte) 0xca
                    || header[1] != (byte) 0xfe
                    || header[2] != (byte) 0xba
                    || header[3] != (byte) 0xbe) {
                return reject(Failure.INVALID_HEADER);
            }
            int major = (header[6] & 0xff) << 8 | (header[7] & 0xff);
            if (major < 45) {
                return reject(Failure.INVALID_HEADER);
            }
            // Ask the actual embedded reader, not the running JVM or a separately maintained version
            // ceiling. Only the version is copied; parsing this small probe does not read the class body.
            System.arraycopy(header, 4, versionProbe, 4, 4);
            try {
                new ClassReader(versionProbe);
            } catch (IllegalArgumentException unsupported) {
                if (unsupportedVersions.size() < MAX_REPORTED_VERSIONS) {
                    unsupportedVersions.add(major);
                }
                return reject(Failure.UNSUPPORTED_VERSION);
            }
            return true;
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return reject(Failure.UNREADABLE_RESOURCE);
        }
    }

    void verify() {
        if (failures.isEmpty()) return;
        long rejected = failures.values().stream().mapToLong(Long::longValue).sum();
        StringBuilder message = new StringBuilder("Application bytecode import incomplete: ")
                .append(rejected)
                .append(" of ")
                .append(checked)
                .append(" class resources rejected (");
        failures.forEach((failure, count) ->
                message.append(failure.description).append(": ").append(count).append("; "));
        message.setLength(message.length() - 2);
        message.append(").");
        if (!unsupportedVersions.isEmpty()) {
            message.append(" Unsupported class-file major versions (up to ")
                    .append(MAX_REPORTED_VERSIONS)
                    .append(" shown): ")
                    .append(unsupportedVersions)
                    .append(".");
        }
        message.append(" Recompile the application or use a BootUI/ArchUnit version that supports its bytecode.");
        throw new IncompleteClassImportException(message.toString());
    }

    private boolean reject(Failure failure) {
        failures.merge(failure, 1L, Long::sum);
        return false;
    }

    private enum Failure {
        TRUNCATED_HEADER("truncated class-file header"),
        INVALID_HEADER("invalid class-file header"),
        UNSUPPORTED_VERSION("unsupported class-file version"),
        UNREADABLE_RESOURCE("unreadable class resource");

        private final String description;

        Failure(String description) {
            this.description = description;
        }
    }
}
