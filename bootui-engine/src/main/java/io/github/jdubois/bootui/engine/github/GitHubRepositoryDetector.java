package io.github.jdubois.bootui.engine.github;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitHubRepositoryDetector {

    private static final Pattern SCP_LIKE_REMOTE = Pattern.compile("(?:[^@]+@)?([^:]+):(.+)");
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    private static final int MAX_METADATA_BYTES = 8192;

    private GitHubRepositoryDetector() {}

    public static Optional<Repository> detect(Path workingDirectory, List<String> allowedApiHosts) {
        return Optional.ofNullable(
                detectRemotes(workingDirectory, allowedApiHosts).origin());
    }

    public static Remotes detectRemotes(Path workingDirectory, List<String> allowedApiHosts) {
        GitLayout layout = findGitLayout(workingDirectory).orElse(null);
        if (layout == null) {
            return new Remotes(null, null, "No local git repository was detected");
        }
        Map<String, Map<String, String>> config = readConfig(layout.configPaths());
        Repository origin = repository(layout, config, allowedApiHosts, "origin");
        Repository upstream = repository(layout, config, allowedApiHosts, "upstream");
        return new Remotes(
                origin,
                upstream,
                upstream == null
                        ? "No valid, allowed GitHub upstream remote was detected; upstream context is unknown."
                        : null);
    }

    private static Repository repository(
            GitLayout layout,
            Map<String, Map<String, String>> config,
            List<String> allowedApiHosts,
            String remoteName) {
        String remoteUrl = value(config, "remote \"" + remoteName + "\"", "url");
        Remote remote = parseRemote(remoteUrl).orElse(null);
        if (remote == null || !isGitHubHost(remote.host(), allowedApiHosts)) {
            return null;
        }

        String localBranch = readLocalBranch(layout.gitDir());
        String upstreamBranch = upstreamBranch(config, localBranch);
        URI apiBaseUri = apiBaseUri(remote.host());
        return new Repository(
                remote.owner(),
                remote.name(),
                remote.owner() + "/" + remote.name(),
                remote.host(),
                apiBaseUri,
                "https://" + credentialHost(remote.host()) + "/" + remote.owner() + "/" + remote.name(),
                localBranch,
                upstreamBranch);
    }

    public static String unavailableReason(Path workingDirectory, List<String> allowedApiHosts) {
        GitLayout layout = findGitLayout(workingDirectory).orElse(null);
        if (layout == null) {
            return "No local git repository was detected";
        }
        Map<String, Map<String, String>> config = readConfig(layout.configPaths());
        String remoteUrl = value(config, "remote \"origin\"", "url");
        Remote remote = parseRemote(remoteUrl).orElse(null);
        if (remote == null) {
            return "No GitHub origin remote was detected";
        }
        if (!isGitHubHost(remote.host(), allowedApiHosts)) {
            return "Origin remote host is not github.com or an allowed GitHub Enterprise host";
        }
        return "No GitHub origin remote was detected";
    }

    private static Optional<GitLayout> findGitLayout(Path workingDirectory) {
        Path current = workingDirectory.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 64; depth++) {
            Path dotGit = current.resolve(".git");
            if (Files.isDirectory(dotGit)) {
                return Optional.of(layout(current, dotGit));
            }
            if (Files.isRegularFile(dotGit)) {
                Path gitDir = readGitDirFile(dotGit).orElse(null);
                if (gitDir != null) {
                    return Optional.of(layout(current, gitDir));
                }
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static GitLayout layout(Path workTree, Path gitDir) {
        List<Path> configPaths = new ArrayList<>();
        configPaths.add(gitDir.resolve("config"));
        readCommonDir(gitDir).ifPresent(commonDir -> {
            Path commonConfig = commonDir.resolve("config");
            if (!configPaths.contains(commonConfig)) {
                configPaths.add(commonConfig);
            }
        });
        return new GitLayout(workTree, gitDir, configPaths);
    }

    private static Optional<Path> readGitDirFile(Path dotGit) {
        try {
            String line = readBounded(dotGit, MAX_METADATA_BYTES).trim();
            if (!line.startsWith("gitdir:")) {
                return Optional.empty();
            }
            String rawPath = line.substring("gitdir:".length()).trim();
            Path gitDir = Path.of(rawPath);
            if (!gitDir.isAbsolute()) {
                gitDir = dotGit.getParent().resolve(gitDir);
            }
            return Optional.of(gitDir.toAbsolutePath().normalize());
        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static Optional<Path> readCommonDir(Path gitDir) {
        Path commonDirFile = gitDir.resolve("commondir");
        if (!Files.isRegularFile(commonDirFile)) {
            return Optional.empty();
        }
        try {
            String rawPath = readBounded(commonDirFile, MAX_METADATA_BYTES).trim();
            Path commonDir = Path.of(rawPath);
            if (!commonDir.isAbsolute()) {
                commonDir = gitDir.resolve(commonDir);
            }
            return Optional.of(commonDir.toAbsolutePath().normalize());
        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static Map<String, Map<String, String>> readConfig(List<Path> configPaths) {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        for (Path configPath : configPaths) {
            if (!Files.isRegularFile(configPath)) {
                continue;
            }
            try {
                String section = null;
                for (String rawLine : readBounded(configPath, MAX_CONFIG_BYTES).split("\\R")) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                        continue;
                    }
                    if (line.startsWith("[") && line.endsWith("]")) {
                        section = line.substring(1, line.length() - 1);
                        values.computeIfAbsent(section, ignored -> new LinkedHashMap<>());
                        continue;
                    }
                    int equals = line.indexOf('=');
                    if (section != null && equals > 0) {
                        String key = line.substring(0, equals).trim();
                        String value = line.substring(equals + 1).trim();
                        values.computeIfAbsent(section, ignored -> new LinkedHashMap<>())
                                .put(key, value);
                    }
                }
            } catch (IOException ex) {
                return Map.of();
            }
        }
        return values;
    }

    private static String readLocalBranch(Path gitDir) {
        Path head = gitDir.resolve("HEAD");
        if (!Files.isRegularFile(head)) {
            return null;
        }
        try {
            String value = readBounded(head, MAX_METADATA_BYTES).trim();
            if (value.startsWith("ref: refs/heads/")) {
                return value.substring("ref: refs/heads/".length());
            }
        } catch (IOException ex) {
            return null;
        }
        return null;
    }

    private static String upstreamBranch(Map<String, Map<String, String>> config, String localBranch) {
        if (localBranch == null) {
            return null;
        }
        String merge = value(config, "branch \"" + localBranch + "\"", "merge");
        if (merge == null) {
            return null;
        }
        return merge.startsWith("refs/heads/") ? merge.substring("refs/heads/".length()) : merge;
    }

    private static String value(Map<String, Map<String, String>> config, String section, String key) {
        Map<String, String> sectionValues = config.get(section);
        return sectionValues == null ? null : sectionValues.get(key);
    }

    private static String readBounded(Path path, int limit) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("Git metadata exceeds the read limit");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Optional<Remote> parseRemote(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return Optional.empty();
        }
        String trimmed = remoteUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("ssh://")) {
            try {
                URI uri = URI.create(trimmed);
                if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    return Optional.empty();
                }
                String host = uri.getHost();
                String path = uri.getPath();
                return parseRemotePath(host, path);
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        Matcher matcher = SCP_LIKE_REMOTE.matcher(trimmed);
        if (matcher.matches()) {
            return parseRemotePath(matcher.group(1), matcher.group(2));
        }
        return Optional.empty();
    }

    private static Optional<Remote> parseRemotePath(String host, String path) {
        if (host == null || path == null) {
            return Optional.empty();
        }
        String normalizedPath = path;
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        while (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        if (normalizedPath.endsWith(".git")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 4);
        }
        String[] segments = normalizedPath.split("/", -1);
        if (segments.length != 2 || !validSegment(segments[0]) || !validSegment(segments[1])) {
            return Optional.empty();
        }
        return Optional.of(new Remote(normalizeHost(host), segments[0], segments[1]));
    }

    private static boolean validSegment(String value) {
        return value.length() <= 100
                && PATH_SEGMENT.matcher(value).matches()
                && !value.equals(".")
                && !value.equals("..");
    }

    private static boolean isGitHubHost(String host, List<String> allowedApiHosts) {
        String normalized = normalizeHost(host);
        if ("github.com".equals(normalized) || "ssh.github.com".equals(normalized)) {
            return true;
        }
        return allowedApiHosts.stream()
                .filter(Objects::nonNull)
                .map(GitHubRepositoryDetector::normalizeHost)
                .anyMatch(allowedHost -> allowedHost.equals(normalized));
    }

    private static URI apiBaseUri(String host) {
        String normalized = normalizeHost(host);
        if ("github.com".equals(normalized) || "ssh.github.com".equals(normalized)) {
            return URI.create("https://api.github.com/");
        }
        return URI.create("https://" + normalized + "/api/v3/");
    }

    static String credentialHost(String host) {
        String normalized = host == null ? "" : normalizeHost(host.trim());
        return "ssh.github.com".equals(normalized) ? "github.com" : normalized;
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    public record Repository(
            String owner,
            String name,
            String fullName,
            String host,
            URI apiBaseUri,
            String htmlUrl,
            String localBranch,
            String upstreamBranch) {}

    public record Remotes(Repository origin, Repository upstream, String upstreamUnavailableReason) {}

    private record Remote(String host, String owner, String name) {}

    private record GitLayout(Path workTree, Path gitDir, List<Path> configPaths) {}
}
