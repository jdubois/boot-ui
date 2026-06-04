package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitHubRepositoryDetector {

    private static final Pattern SCP_LIKE_REMOTE = Pattern.compile("(?:[^@]+@)?([^:]+):(.+)");

    private GitHubRepositoryDetector() {}

    static Optional<Repository> detect(Path workingDirectory, BootUiProperties properties) {
        GitLayout layout = findGitLayout(workingDirectory).orElse(null);
        if (layout == null) {
            return Optional.empty();
        }
        Map<String, Map<String, String>> config = readConfig(layout.configPaths());
        String remoteUrl = value(config, "remote \"origin\"", "url");
        Remote remote = parseRemote(remoteUrl).orElse(null);
        if (remote == null || !isGitHubHost(remote.host(), properties.getGithub())) {
            return Optional.empty();
        }

        String localBranch = readLocalBranch(layout.gitDir());
        String upstreamBranch = upstreamBranch(config, localBranch);
        URI apiBaseUri = apiBaseUri(remote.host());
        return Optional.of(new Repository(
                remote.owner(),
                remote.name(),
                remote.owner() + "/" + remote.name(),
                remote.host(),
                apiBaseUri,
                "https://" + htmlHost(remote.host()) + "/" + remote.owner() + "/" + remote.name(),
                localBranch,
                upstreamBranch));
    }

    static String unavailableReason(Path workingDirectory, BootUiProperties properties) {
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
        if (!isGitHubHost(remote.host(), properties.getGithub())) {
            return "Origin remote host is not github.com or an allowed GitHub Enterprise host";
        }
        return "No GitHub origin remote was detected";
    }

    private static Optional<GitLayout> findGitLayout(Path workingDirectory) {
        Path current = workingDirectory.toAbsolutePath().normalize();
        while (current != null) {
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
            String line = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
            if (!line.startsWith("gitdir:")) {
                return Optional.empty();
            }
            String rawPath = line.substring("gitdir:".length()).trim();
            Path gitDir = Path.of(rawPath);
            if (!gitDir.isAbsolute()) {
                gitDir = dotGit.getParent().resolve(gitDir);
            }
            return Optional.of(gitDir.toAbsolutePath().normalize());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private static Optional<Path> readCommonDir(Path gitDir) {
        Path commonDirFile = gitDir.resolve("commondir");
        if (!Files.isRegularFile(commonDirFile)) {
            return Optional.empty();
        }
        try {
            String rawPath =
                    Files.readString(commonDirFile, StandardCharsets.UTF_8).trim();
            Path commonDir = Path.of(rawPath);
            if (!commonDir.isAbsolute()) {
                commonDir = gitDir.resolve(commonDir);
            }
            return Optional.of(commonDir.toAbsolutePath().normalize());
        } catch (IOException ex) {
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
                for (String rawLine : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
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
            String value = Files.readString(head, StandardCharsets.UTF_8).trim();
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

    private static Optional<Remote> parseRemote(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return Optional.empty();
        }
        String trimmed = remoteUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("ssh://")) {
            try {
                URI uri = URI.create(trimmed);
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
        if (normalizedPath.endsWith(".git")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 4);
        }
        String[] segments = normalizedPath.split("/");
        if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Remote(normalizeHost(host), segments[0], segments[1]));
    }

    private static boolean isGitHubHost(String host, BootUiProperties.GitHub properties) {
        String normalized = normalizeHost(host);
        if ("github.com".equals(normalized) || "ssh.github.com".equals(normalized)) {
            return true;
        }
        return Arrays.stream(properties.getAllowedApiHosts())
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

    private static String htmlHost(String host) {
        String normalized = normalizeHost(host);
        return "ssh.github.com".equals(normalized) ? "github.com" : normalized;
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    record Repository(
            String owner,
            String name,
            String fullName,
            String host,
            URI apiBaseUri,
            String htmlUrl,
            String localBranch,
            String upstreamBranch) {}

    private record Remote(String host, String owner, String name) {}

    private record GitLayout(Path workTree, Path gitDir, List<Path> configPaths) {}
}
