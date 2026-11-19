package io.github.jdubois.bootui.autoconfigure.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads and writes BootUI runtime configuration overrides to a local file.
 *
 * <p>The file is treated as developer-local: BootUI never commits it, never sends
 * it anywhere, and recommends that the path is git-ignored. By default it is
 * {@code .bootui/application-bootui.properties} in the working directory.</p>
 */
public class ConfigOverridesFileStore {

    private final Path file;

    public ConfigOverridesFileStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public Map<String, Object> load() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read BootUI overrides file: " + file, e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            result.put(name, props.getProperty(name));
        }
        return result;
    }

    public synchronized void save(Map<String, Object> overrides) {
        Path temporary = null;
        try {
            Path target = file.toAbsolutePath().normalize();
            Path directory = target.getParent();
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(target)) {
                throw new IOException("Refusing to overwrite a symbolic link");
            }
            temporary = ownerOnlyTemporaryFile(directory);
            Properties props = new Properties();
            overrides.forEach((k, v) -> props.setProperty(k, v == null ? "" : String.valueOf(v)));
            try (OutputStream out = Files.newOutputStream(temporary)) {
                props.store(out, "BootUI local runtime overrides — do not commit.");
            }
            createIgnoreFile(directory, target.getFileName().toString());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write BootUI overrides file: " + file, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ex) {
                    throw new IllegalStateException("Unable to clean BootUI overrides temporary file", ex);
                }
            }
        }
    }

    private static Path ownerOnlyTemporaryFile(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            return Files.createTempFile(
                    directory,
                    ".bootui-overrides-",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Path temporary = Files.createTempFile(directory, ".bootui-overrides-", ".tmp");
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
            if (acl == null) {
                throw new IOException("Owner-only file permissions are unavailable on this filesystem");
            }
            acl.setAcl(List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(temporary))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build()));
            return temporary;
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        }
    }

    private static void createIgnoreFile(Path directory, String fileName) throws IOException {
        Path ignore = directory.resolve(".gitignore");
        if (!Files.exists(ignore)) {
            try {
                Files.writeString(
                        ignore,
                        "/" + fileName + "\n*.hprof\n/.bootui-overrides-*.tmp\n",
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                // Another first write created the ignore file; never overwrite local rules.
            }
        }
    }

    public synchronized void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to delete BootUI overrides file: " + file, e);
        }
    }
}
