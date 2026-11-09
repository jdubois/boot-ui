package io.github.jdubois.bootui.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Recognizes installer ownership without executing launchers or trusting an environment path by itself. */
record Installation(String kind, Path jar, Path launcher, String repository) {
    static final String RECORD = "installation-v1";
    static final String JBANG_COMMAND = "jbang app install --force --fresh bootui@jdubois/boot-ui";

    static Installation current(Map<String, String> environment) throws IOException {
        try {
            Path location = Path.of(BootUiCli.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return detect(location, environment);
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("Cannot locate the running CLI jar.", invalid);
        }
    }

    static Installation detect(Path location, Map<String, String> environment) throws IOException {
        Path jar = location.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            return new Installation("unmanaged", jar, null, null);
        }
        jar = jar.toRealPath();
        Path record = jar.getParent().resolve(RECORD);
        if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unrecognized BootUI installation record. Reinstall explicitly to repair it.");
            }
            List<String> lines = readSmall(record).lines().toList();
            if (lines.size() != 4 || !lines.get(0).equals("bootui-install-v1")) {
                throw new IOException("Unrecognized BootUI installation record. Reinstall explicitly to repair it.");
            }
            Path recordedJar = Path.of(lines.get(1));
            Path launcher = Path.of(lines.get(2));
            if (!recordedJar.isAbsolute()
                    || !launcher.isAbsolute()
                    || !sameJar(recordedJar, jar)
                    || !scriptLauncher(launcherText(launcher), jar)) {
                throw new IOException(
                        "Installation record does not match the running jar and installer launcher. Nothing was changed.");
            }
            return new Installation("installer", jar, launcher.toRealPath(), lines.get(3));
        }
        List<Path> candidates = launchers(environment);
        List<Path> managed = new ArrayList<>();
        boolean jbang = false;
        for (Path launcher : candidates) {
            String text = launcherText(launcher);
            if (text == null) {
                continue;
            }
            if (scriptLauncher(text, jar)) {
                Path real = launcher.toRealPath();
                if (!managed.contains(real)) {
                    managed.add(real);
                }
            } else {
                jbang |= text.contains("jbang") && text.contains("bootui@jdubois/boot-ui");
            }
        }
        if (managed.size() > 1) {
            throw new IOException(
                    "More than one installer launcher references this jar. Reinstall explicitly to resolve ownership.");
        }
        if (managed.size() == 1) {
            return new Installation("installer", jar, managed.get(0), null);
        }
        // JBang also resolves jars through Maven's cache. A cache location alone is not ownership evidence.
        boolean cached = jar.toString().contains(File.separator + "cache" + File.separator)
                || jar.toString().contains(File.separator + "repository" + File.separator);
        return new Installation(jbang && cached ? "jbang" : "unmanaged", jar, null, null);
    }

    private static List<Path> launchers(Map<String, String> environment) {
        List<Path> directories = new ArrayList<>();
        String path = environment.get("PATH");
        if (path == null) {
            path = environment.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
        }
        for (String part : path.split(Pattern.quote(File.pathSeparator))) {
            Path entry = pathEntry(part);
            if (entry != null) {
                directories.add(entry);
            }
        }
        for (String variable : List.of("BOOTUI_BIN_DIR")) {
            String value = environment.get(variable);
            if (value != null && !value.isBlank()) {
                directories.add(Path.of(value));
            }
        }
        String state = environment.get("BOOTUI_INSTALL_DIR");
        if (state != null && !state.isBlank()) {
            directories.add(Path.of(state, "bin"));
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            directories.add(Path.of(home, ".local", "bin"));
        }
        String local = environment.get("LOCALAPPDATA");
        if (local != null) {
            directories.add(Path.of(local, "BootUI", "bin"));
        }
        List<Path> result = new ArrayList<>();
        for (Path directory : directories) {
            result.add(directory.resolve("bootui"));
            result.add(directory.resolve("bootui.cmd"));
        }
        return result;
    }

    private static Path pathEntry(String entry) {
        String text = entry;
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text);
        } catch (java.nio.file.InvalidPathException invalidEntry) {
            // Like an absent directory, an unusable PATH entry cannot identify an installation.
            return null;
        }
    }

    private static String launcherText(Path launcher) throws IOException {
        if (!Files.isRegularFile(launcher) || !Files.isReadable(launcher) || Files.size(launcher) > 16 * 1024) {
            return null;
        }
        return readSmall(launcher.toRealPath());
    }

    private static boolean sameJar(Path candidate, Path jar) throws IOException {
        return Files.isRegularFile(candidate) && Files.isSameFile(candidate, jar);
    }

    private static boolean scriptLauncher(String text, Path jar) throws IOException {
        if (text == null || !text.contains("Generated by the BootUI CLI installer.")) {
            return false;
        }
        for (String line : text.lines().toList()) {
            String target = null;
            if (line.startsWith("JAR=\"") && line.endsWith("\"")) {
                target = line.substring(5, line.length() - 1);
            } else if (line.startsWith("set \"BOOTUI_JAR=") && line.endsWith("\"")) {
                target = line.substring("set \"BOOTUI_JAR=".length(), line.length() - 1);
            }
            Path candidate = target == null ? null : pathEntry(target);
            if (candidate != null && sameJar(candidate, jar)) {
                return true;
            }
        }
        return false;
    }

    private static String readSmall(Path file) throws IOException {
        if (Files.size(file) > 16 * 1024) {
            throw new IOException("Installation file exceeds 16384 bytes: " + file);
        }
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(16 * 1024 + 1);
            if (bytes.length > 16 * 1024) {
                throw new IOException("Installation file exceeds 16384 bytes: " + file);
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
