package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/** Explicit update operations never execute a downloaded script or mutate a package manager's cache. */
final class UpdateCommand implements Callable<Integer> {
    private final CliContext context;
    private final String installed;
    private final Installation installation;
    private final boolean windows;
    private boolean check;

    UpdateCommand(CliContext context, String installed, Installation installation) {
        this(
                context,
                installed,
                installation,
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("windows"));
    }

    UpdateCommand(CliContext context, String installed, Installation installation, boolean windows) {
        this.context = context;
        this.installed = installed;
        this.installation = installation;
        this.windows = windows;
    }

    static void add(CliContext context, CommandSpec root) {
        UpdateCommand command = new UpdateCommand(context, BootUiCli.version(), null);
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("update");
        CommandTree.configure(context, spec);
        spec.usageMessage()
                .description(
                        "Check for or install a newer CLI. No running application is needed.",
                        "Only recognized installer-owned jars are replaced, after mandatory checksum verification.",
                        "JBang and Windows installs receive manual update guidance; unknown installs are not modified.")
                .footer(
                        "--check exits: 0 current, 3 update available, 1 error.",
                        "Updating replaces an executable: agents must obtain the user's approval first.");
        spec.addOption(OptionSpec.builder("--check")
                .type(boolean.class)
                .description("Check Maven metadata only; do not download a jar or change local files.")
                .setter(CommandTree.setter((Boolean value) -> command.check = Boolean.TRUE.equals(value)))
                .build());
        root.addSubcommand("update", new CommandLine(spec));
    }

    @Override
    public Integer call() {
        return run(check);
    }

    int run(boolean checkOnly) {
        String latest = null;
        String kind = "unmanaged";
        try {
            if ("dev".equals(installed)
                    || !ReleaseRepository.VERSION.matcher(installed).matches()) {
                throw new IOException("This is a development build, not a released CLI. Install a release explicitly.");
            }
            Installation target = installation == null ? Installation.current(context.environment()) : installation;
            kind = target.kind();
            String configured = context.environment().get("BOOTUI_MAVEN_REPO");
            String repository = configured != null && !configured.isBlank()
                    ? configured
                    : target.repository() != null ? target.repository() : ReleaseRepository.DEFAULT;
            ReleaseRepository releases = new ReleaseRepository(repository);
            latest = releases.latest(installed);
            if (!UpdateCheck.isNewer(latest, installed)) {
                report(
                        "current",
                        latest,
                        kind,
                        "The installed CLI is current or newer than the repository release.",
                        null);
                return ExitCodes.SUCCESS;
            }
            if (checkOnly) {
                report("available", latest, kind, "A newer CLI is available.", null);
                return ExitCodes.UPDATE_AVAILABLE;
            }
            if ("jbang".equals(kind)) {
                report(
                        "manual-action-required",
                        latest,
                        kind,
                        "JBang owns this installation. Run the following command after approval:",
                        Installation.JBANG_COMMAND);
                return ExitCodes.SUCCESS;
            }
            if (!"installer".equals(kind)) {
                throw new IOException(
                        "Automatic update is unsupported for this installation. Download the matching bootui-cli-"
                                + latest
                                + "-all.jar from your Maven repository and replace your manually managed jar explicitly.");
            }
            if (windows) {
                String command = "& .\\install.ps1 -Version " + powershell(latest)
                        + " -InstallDir " + powershell(target.jar().getParent().toString())
                        + " -BinDir " + powershell(target.launcher().getParent().toString())
                        + " -MavenRepo " + powershell(repository) + " -NoPathUpdate";
                report(
                        "manual-action-required",
                        latest,
                        kind,
                        "Windows cannot replace the CLI jar while it is running. Download and review "
                                + "https://www.julien-dubois.com/boot-ui/install.ps1 as install.ps1, then run this PowerShell command after the CLI exits:",
                        command);
                return ExitCodes.SUCCESS;
            }
            replace(target, releases, latest);
            report("updated", latest, kind, "CLI updated. The next invocation uses the new version.", null);
            return ExitCodes.SUCCESS;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            report("failed", latest, kind, "Update interrupted. No replacement was committed.", null);
            return ExitCodes.ERROR;
        } catch (OverlappingFileLockException busy) {
            report("failed", latest, kind, "Another BootUI update is running. Retry after it completes.", null);
            return ExitCodes.ERROR;
        } catch (IOException | IllegalArgumentException failure) {
            String message = failure.getMessage();
            if (message == null || message.isBlank()) {
                message = failure.getClass().getSimpleName();
            }
            if (failure instanceof java.nio.file.FileSystemException) {
                message = "Cannot update the installation. Check directory permissions and file locks: " + message;
            }
            report("failed", latest, kind, message, null);
            return ExitCodes.ERROR;
        }
    }

    private static String powershell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private void replace(Installation target, ReleaseRepository releases, String latest)
            throws IOException, InterruptedException {
        Path jar = target.jar();
        Path lockPath = jar.getParent().resolve("update.lock");
        try (FileChannel channel = FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Another BootUI update is running. Retry after it completes.");
            }
            Installation live = Installation.detect(jar, context.environment());
            if (!target.equals(live)) {
                throw new IOException("Installation ownership changed. Restart the CLI before retrying.");
            }
            validateJar(jar, installed);
            var before = Files.readAttributes(
                    jar, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path temporary = Files.createTempFile(jar.getParent(), ".bootui-update-", ".jar");
            try {
                releases.download(latest, installed, temporary);
                validateJar(temporary, latest);
                if (Files.getFileStore(jar).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(jar));
                }
                try (FileChannel downloaded = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    downloaded.force(true);
                }
                var after = Files.readAttributes(
                        jar, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!Objects.equals(before.fileKey(), after.fileKey())
                        || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !target.equals(Installation.detect(jar, context.environment()))) {
                    throw new IOException(
                            "Installation changed during download. Nothing was replaced; restart the CLI.");
                }
                try {
                    Files.move(temporary, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException failure) {
                    throw new IOException(
                            "Cannot atomically replace the installed jar. Close other BootUI processes and check file permissions. The old installation was not deleted.",
                            failure);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static void validateJar(Path path, String version) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var manifest = jar.getManifest();
            if (manifest == null
                    || !"io.github.jdubois.bootui.cli.BootUiCli"
                            .equals(manifest.getMainAttributes().getValue("Main-Class"))
                    || !version.equals(manifest.getMainAttributes().getValue("Implementation-Version"))
                    || jar.getJarEntry("io/github/jdubois/bootui/cli/BootUiCli.class") == null) {
                throw new IOException("Jar is not the expected runnable BootUI CLI version " + version + ".");
            }
        }
    }

    private void report(String status, String latest, String kind, String message, String command) {
        if (context.options().json(context.terminal())) {
            Map<String, JsonValue> result = new LinkedHashMap<>();
            result.put("status", JsonValue.of(status));
            result.put("installedVersion", JsonValue.of(installed));
            result.put("latestVersion", JsonValue.of(latest));
            result.put("installation", JsonValue.of(kind));
            result.put("message", JsonValue.of(message));
            if (command != null) {
                result.put("command", JsonValue.of(command));
            }
            context.out().println(JsonValue.object(result).toJson());
        } else {
            var output = "failed".equals(status) ? context.err() : context.out();
            output.println("bootui: " + message);
            output.println("        Installed: " + installed + (latest == null ? "" : "; latest: " + latest)
                    + "; installation: " + kind);
            if (command != null) {
                output.println("        " + command);
            }
        }
        context.out().flush();
        context.err().flush();
    }
}
