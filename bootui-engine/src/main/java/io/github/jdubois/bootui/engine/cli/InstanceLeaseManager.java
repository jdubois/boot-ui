package io.github.jdubois.bootui.engine.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of a local BootUI instance lease file under {@code ~/.bootui/instances/<instanceId>.json}.
 *
 * <p>Writes the lease atomically with {@code 0600} permissions inside an {@code 0700} directory after the web server
 * port is bound, and deletes the file on clean shutdown or JVM exit.
 */
public final class InstanceLeaseManager implements AutoCloseable {

    /** Environment variable overriding the {@code ~/.bootui} home directory. */
    public static final String BOOTUI_HOME_ENV = "BOOTUI_HOME";

    private static final Logger log = Logger.getLogger(InstanceLeaseManager.class.getName());

    private static final Set<PosixFilePermission> DIR_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private final Path instancesDir;
    private volatile Path leaseFile;
    private volatile Thread shutdownHook;

    public InstanceLeaseManager(Path instancesDir) {
        this.instancesDir =
                instancesDir == null ? null : instancesDir.toAbsolutePath().normalize();
    }

    /**
     * Resolves the lease directory from explicit configuration ({@code bootui.cli.instances-dir}),
     * {@code BOOTUI_HOME}, or {@code ~/.bootui/instances}.
     */
    public static Path resolveInstancesDir(String configuredInstancesDir) {
        return resolveInstancesDir(configuredInstancesDir, System.getenv(), System.getProperty("user.home"));
    }

    /**
     * Resolves the lease directory from the supplied configuration, environment, and user home directory.
     */
    public static Path resolveInstancesDir(
            String configuredInstancesDir, Map<String, String> environment, String userHome) {
        if (configuredInstancesDir != null && !configuredInstancesDir.isBlank()) {
            return Path.of(configuredInstancesDir.trim()).toAbsolutePath().normalize();
        }
        String bootUiHome = environment == null ? null : environment.get(BOOTUI_HOME_ENV);
        if (bootUiHome != null && !bootUiHome.isBlank()) {
            return Path.of(bootUiHome.trim(), "instances").toAbsolutePath().normalize();
        }
        if (userHome != null && !userHome.isBlank()) {
            return Path.of(userHome.trim(), ".bootui", "instances")
                    .toAbsolutePath()
                    .normalize();
        }
        return null;
    }

    /** The directory where this manager writes lease files, or {@code null} if none could be resolved. */
    public Path instancesDir() {
        return instancesDir;
    }

    /** The currently registered lease file path, or {@code null} if none is active. */
    public Path leaseFile() {
        return leaseFile;
    }

    /**
     * Atomically writes {@code lease} to {@code <instancesDir>/<instanceId>.json} with {@code 0600} permissions.
     *
     * @return the path of the written lease file, or {@code null} if the directory was unavailable
     * @throws IOException if the lease file could not be written safely
     */
    public synchronized Path write(InstanceLease lease) throws IOException {
        Objects.requireNonNull(lease, "lease");
        if (instancesDir == null) {
            return null;
        }
        ensureOwnerDirectory(instancesDir);
        Path target = instancesDir.resolve(lease.fileName()).toAbsolutePath().normalize();
        if (!instancesDir.equals(target.getParent())) {
            throw new IOException("Refusing to write lease outside instances directory: " + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to overwrite symbolic link lease file: " + target);
        }

        boolean posix = supportsPosix(instancesDir);
        Path temp = posix
                ? Files.createTempFile(
                        instancesDir,
                        ".lease-" + lease.instanceId() + "-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
                : Files.createTempFile(instancesDir, ".lease-" + lease.instanceId() + "-", ".tmp");
        try {
            if (posix) {
                Files.setPosixFilePermissions(temp, FILE_PERMISSIONS);
            }
            Files.writeString(temp, lease.toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
            if (posix) {
                Files.setPosixFilePermissions(temp, FILE_PERMISSIONS);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (posix) {
                Files.setPosixFilePermissions(target, FILE_PERMISSIONS);
            }
        } finally {
            Files.deleteIfExists(temp);
        }

        this.leaseFile = target;
        registerShutdownHook(target);
        return target;
    }

    /** Writes {@code lease} best-effort, logging at FINE/WARNING without failing application startup. */
    public Path writeQuietly(InstanceLease lease) {
        try {
            return write(lease);
        } catch (IOException | RuntimeException ex) {
            log.log(Level.FINE, "Could not write BootUI instance lease file", ex);
            return null;
        }
    }

    @Override
    public synchronized void close() {
        Thread hook = this.shutdownHook;
        this.shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // JVM is already shutting down; the hook is running or about to run.
            } catch (SecurityException ignored) {
                // Ignore if security manager prevents hook removal.
            }
        }
        Path current = this.leaseFile;
        this.leaseFile = null;
        if (current != null) {
            try {
                Files.deleteIfExists(current);
            } catch (IOException ex) {
                log.log(Level.FINE, "Could not delete BootUI instance lease file on shutdown", ex);
            }
        }
    }

    private void registerShutdownHook(Path target) {
        Thread previous = this.shutdownHook;
        if (previous != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(previous);
            } catch (IllegalStateException | SecurityException ignored) {
                // Ignore if JVM is already shutting down.
            }
        }
        Thread hook = new Thread(
                () -> {
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException ignored) {
                        // Best-effort cleanup on JVM exit.
                    }
                },
                "bootui-instance-lease-cleanup");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            this.shutdownHook = hook;
        } catch (IllegalStateException | SecurityException ignored) {
            this.shutdownHook = null;
        }
    }

    private static void ensureOwnerDirectory(Path instancesDir) throws IOException {
        Path parent = instancesDir.getParent();
        if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(parent)) {
            throw new IOException("Refusing to use symbolic link parent directory: " + parent);
        }
        if (Files.exists(instancesDir, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(instancesDir)) {
            throw new IOException("Refusing to use symbolic link instances directory: " + instancesDir);
        }
        Files.createDirectories(instancesDir);
        if (!Files.isDirectory(instancesDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Instances path is not a regular directory: " + instancesDir);
        }
        if (supportsPosix(instancesDir)) {
            if (parent != null
                    && parent.getFileName() != null
                    && ".bootui".equals(parent.getFileName().toString())
                    && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(parent, DIR_PERMISSIONS);
            }
            Files.setPosixFilePermissions(instancesDir, DIR_PERMISSIONS);
        }
    }

    private static boolean supportsPosix(Path path) {
        try {
            return Files.getFileStore(path).supportsFileAttributeView("posix");
        } catch (IOException ex) {
            return false;
        }
    }
}
