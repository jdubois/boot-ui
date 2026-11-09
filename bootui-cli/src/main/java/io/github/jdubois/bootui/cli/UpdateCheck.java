package io.github.jdubois.bootui.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tells the user, once a newer {@code bootui} has been published, that one exists.
 *
 * <p>A command-line tool installed by a script has no other way to learn it is out of date, and the answer
 * is already public: the {@code maven-metadata.xml} the installer itself reads to pick a version.
 *
 * <p>What it must never do is get in the way. So the notice is printed from a file the last check left
 * behind — the run that prints it makes no network call of its own — the refresh happens on a daemon thread
 * alongside the command and is abandoned rather than waited for, nothing is written to standard output, the
 * exit code is untouched, and the whole thing is inert unless a terminal is watching. A piped command, a CI
 * job, and {@code BOOTUI_NO_UPDATE_CHECK=1} all get silence and no traffic.
 */
final class UpdateCheck {

    static final String DISABLE_ENV = "BOOTUI_NO_UPDATE_CHECK";
    static final String STATE_DIR_ENV = "BOOTUI_INSTALL_DIR";
    static final String REPOSITORY_ENV = "BOOTUI_MAVEN_REPO";

    private static final String DEFAULT_REPOSITORY = "https://repo1.maven.org/maven2";
    private static final String METADATA_PATH = "/com/julien-dubois/bootui/bootui-cli/maven-metadata.xml";
    private static final String LATEST_VERSION_FILE = "latest-version";
    private static final String STAMP_FILE = "update-checked-at";
    private static final Duration MAX_AGE = Duration.ofDays(1);
    private static final Duration SHUTDOWN_GRACE = Duration.ofMillis(250);
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]*");

    private final boolean enabled;
    private final String installedVersion;
    private final Path stateDir;
    private final URI metadataUrl;
    private final String updateCommand;

    private volatile Thread refresh;

    UpdateCheck(boolean enabled, String installedVersion, Path stateDir, URI metadataUrl, String updateCommand) {
        this.enabled = enabled;
        this.installedVersion = installedVersion;
        this.stateDir = stateDir;
        this.metadataUrl = metadataUrl;
        this.updateCommand = updateCommand;
    }

    /**
     * Decides whether this run should check at all, and where it would look.
     *
     * <p>A version of {@code dev} means the jar was not built by the release, so there is nothing meaningful
     * to compare against, and a run with no terminal is someone's script or pipeline, which did not ask.
     */
    static UpdateCheck create(String installedVersion, Map<String, String> environment, boolean terminal) {
        boolean enabled = terminal
                && !isSet(environment.get(DISABLE_ENV))
                && installedVersion != null
                && VERSION.matcher(installedVersion).matches()
                && !"dev".equals(installedVersion);
        Path stateDir = null;
        URI metadataUrl = null;
        if (enabled) {
            stateDir = stateDirectory(environment);
            metadataUrl = metadataUrl(environment);
            enabled = stateDir != null && metadataUrl != null;
        }
        return new UpdateCheck(enabled, installedVersion, stateDir, metadataUrl, updateCommand());
    }

    /** Starts the background refresh, if one is due, so it overlaps the command rather than following it. */
    void startRefreshIfDue() {
        if (!enabled || !isDue()) {
            return;
        }
        Thread thread = new Thread(this::refresh, "bootui-update-check");
        thread.setDaemon(true);
        refresh = thread;
        thread.start();
    }

    /** Prints what the last completed check found, if it found a newer release. */
    void report(PrintWriter err) {
        if (!enabled) {
            return;
        }
        String latest = readLatestVersion();
        if (latest == null || !isNewer(latest, installedVersion)) {
            return;
        }
        err.println("bootui: version " + latest + " is available; you have " + installedVersion + ".");
        err.println("        Update with: " + updateCommand);
        err.println("        Silence this with " + DISABLE_ENV + "=1.");
        err.flush();
    }

    /**
     * Gives an in-flight refresh a short moment to finish before the JVM exits.
     *
     * <p>The thread is a daemon, so exiting would simply kill it. That is the right trade — the answer is
     * never worth delaying a command for — but a check that is all but done is worth a quarter of a second,
     * at most once a day. A check cut short is not recorded, so the next run simply tries again.
     */
    void awaitRefresh() {
        Thread thread = refresh;
        if (thread == null) {
            return;
        }
        try {
            thread.join(SHUTDOWN_GRACE.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Asks the repository what the newest release is and records the answer.
     *
     * <p>The stamp is written last, and only when the question was actually settled: an attempt cut short by
     * the JVM exiting should be tried again on the next run, while an unreachable repository — which fails
     * quickly — should not be asked again until tomorrow.
     */
    void refresh() {
        String latest = null;
        try {
            latest = fetchLatestVersion();
        } catch (Exception unreachable) {
            // An update check is a courtesy. It never becomes the reason a command failed, and it never
            // speaks up about its own troubles: the user asked about their application, not about us.
        }
        try {
            if (latest != null) {
                writeAtomically(stateDir.resolve(LATEST_VERSION_FILE), latest);
            }
            writeAtomically(stateDir.resolve(STAMP_FILE), Instant.now().toString());
        } catch (Exception unwritable) {
            // A read-only or missing state directory simply means no notice, on this run and the next.
        }
    }

    private String fetchLatestVersion() throws Exception {
        return ReleaseRepository.latest(metadataUrl, installedVersion);
    }

    private boolean isDue() {
        try {
            Path stamp = stateDir.resolve(STAMP_FILE);
            if (!Files.isReadable(stamp)) {
                return true;
            }
            return Files.getLastModifiedTime(stamp)
                    .toInstant()
                    .isBefore(Instant.now().minus(MAX_AGE));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readLatestVersion() {
        try {
            Path cached = stateDir.resolve(LATEST_VERSION_FILE);
            if (!Files.isReadable(cached)) {
                return null;
            }
            String value = Files.readString(cached).trim();
            return VERSION.matcher(value).matches() ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary =
                Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content + System.lineSeparator());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * True when {@code candidate} is a higher version than {@code current}.
     *
     * <p>Compares dot-separated numbers, and treats a qualifier such as {@code -rc1} as older than the same
     * version without one. Anything it cannot read as a number means no notice: staying quiet is always safe,
     * and telling someone to "update" to an older build is not.
     */
    static boolean isNewer(String candidate, String current) {
        if (candidate == null || current == null) {
            return false;
        }
        String candidateCore = core(candidate);
        String currentCore = core(current);
        String[] left = candidateCore.split("\\.", -1);
        String[] right = currentCore.split("\\.", -1);
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            long leftPart;
            long rightPart;
            try {
                leftPart = index < left.length ? Long.parseLong(left[index]) : 0;
                rightPart = index < right.length ? Long.parseLong(right[index]) : 0;
            } catch (NumberFormatException notANumber) {
                return false;
            }
            if (leftPart != rightPart) {
                return leftPart > rightPart;
            }
        }
        return candidateCore.length() == candidate.length() && currentCore.length() != current.length();
    }

    private static String core(String version) {
        int qualifier = version.indexOf('-');
        return qualifier < 0 ? version : version.substring(0, qualifier);
    }

    /**
     * Where the state file lives: beside the jar the installer put there.
     *
     * <p>The installers already own {@code ~/.bootui} and {@code %LOCALAPPDATA%\BootUI}, and read
     * {@code BOOTUI_INSTALL_DIR} for a chosen location, so following the same rule keeps everything one
     * install wrote in one place — and makes {@code --uninstall} enough to remove it.
     */
    private static Path stateDirectory(Map<String, String> environment) {
        try {
            String configured = environment.get(STATE_DIR_ENV);
            if (isSet(configured)) {
                return Path.of(configured);
            }
            if (isWindows()) {
                String localAppData = environment.get("LOCALAPPDATA");
                if (isSet(localAppData)) {
                    return Path.of(localAppData, "BootUI");
                }
            }
            String home = System.getProperty("user.home");
            return isSet(home) ? Path.of(home, ".bootui") : null;
        } catch (Exception unusablePath) {
            return null;
        }
    }

    private static URI metadataUrl(Map<String, String> environment) {
        try {
            String repository = environment.get(REPOSITORY_ENV);
            String base = isSet(repository) ? repository.trim() : DEFAULT_REPOSITORY;
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            URI url = URI.create(base + METADATA_PATH);
            String scheme = url.getScheme();
            boolean usable = url.getHost() != null && ("https".equals(scheme) || "http".equals(scheme));
            return usable ? url : null;
        } catch (Exception unusableUrl) {
            return null;
        }
    }

    private static String updateCommand() {
        return "bootui update";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
