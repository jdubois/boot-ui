package io.github.jdubois.bootui.engine.github;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSON-free traversal state for one repository alert endpoint; never follows a server-supplied URI directly. */
public final class GitHubAlertPagination {

    private static final Pattern LINK = Pattern.compile("\\s*<([^<>\\s]+)>\\s*;\\s*rel=\"(next|prev|first|last)\"\\s*");

    private final String path;
    private final URI endpoint;
    private final URI canonicalEndpoint;
    private final Set<String> visited = new HashSet<>();
    private final Set<String> pageDigests = new HashSet<>();
    private final MessageDigest digest;
    private String current = "page=1";
    private Mode mode;
    private int page = 1;
    private Integer lastPage;
    private String lastCursor;
    private int count;

    public GitHubAlertPagination(URI apiBaseUri, String path) {
        this(apiBaseUri, path, null);
    }

    /** The optional repository ID must come from fetched repository metadata, never from a Link. */
    public GitHubAlertPagination(URI apiBaseUri, String path, Long repositoryId) {
        this.path = path.substring(0, path.indexOf('?'));
        this.endpoint = apiBaseUri.resolve(this.path);
        int suffixStart = this.path.lastIndexOf('/', this.path.lastIndexOf('/') - 1);
        this.canonicalEndpoint = repositoryId != null && repositoryId > 0
                ? apiBaseUri.resolve("repositories/" + repositoryId + this.path.substring(suffixStart))
                : null;
        visited.add(current);
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("The JDK must support SHA-256", ex);
        }
    }

    /** Retains only a digest, not a second copy of code-scanning or secret-scanning payloads. */
    public void acceptPage(int size, String contents) throws IOException {
        if (size < 0 || size > 100) {
            throw invalid();
        }
        String fingerprint = HexFormat.of().formatHex(digest.digest(contents.getBytes(StandardCharsets.UTF_8)));
        if (!pageDigests.add(fingerprint)) {
            throw new IOException("GitHub alert pagination repeated a response page.");
        }
        try {
            count = Math.addExact(count, size);
        } catch (ArithmeticException ex) {
            throw new IOException("GitHub alert count exceeds the supported range.");
        }
    }

    public int count() {
        return count;
    }

    /** Returns a reconstructed API-relative path, or null only when traversal is complete. */
    public String nextPath(HttpHeaders headers) throws IOException {
        Map<String, String> relations = new HashMap<>();
        for (String header : headers.allValues("Link")) {
            for (String part : header.split(",", -1)) {
                Matcher matcher = LINK.matcher(part);
                if (!matcher.matches()) {
                    throw invalid();
                }
                String relation = matcher.group(2);
                String selector = selector(matcher.group(1), relation.equals("first") || relation.equals("prev"));
                if (relations.putIfAbsent(relation, selector) != null) {
                    throw invalid();
                }
            }
        }
        String last = relations.get("last");
        if (last != null) {
            if (last.startsWith("page=")) {
                requireMode(Mode.NUMBERED);
                int hint = pageNumber(last.substring(5));
                if (hint < page || (lastPage != null && hint < lastPage)) {
                    throw invalid();
                }
                lastPage = hint;
            } else if (last.startsWith("after=")) {
                requireMode(Mode.CURSOR);
                if ((lastCursor != null && !lastCursor.equals(last))
                        || (visited.contains(last) && !current.equals(last))) {
                    throw invalid();
                }
                lastCursor = last;
            } else {
                throw invalid();
            }
        }
        String next = relations.get("next");
        if (next == null && lastPage != null && page < lastPage) {
            next = "page=" + (page + 1);
        }
        if (next == null) {
            if (lastCursor != null && !lastCursor.equals(current)) {
                throw invalid();
            }
            return null;
        }
        if (next.startsWith("page=")) {
            requireMode(Mode.NUMBERED);
            int nextPage = pageNumber(next.substring(5));
            if (page == Integer.MAX_VALUE || nextPage != page + 1 || (lastPage != null && nextPage > lastPage)) {
                throw invalid();
            }
            page = nextPage;
        } else if (next.startsWith("after=")) {
            requireMode(Mode.CURSOR);
            if (current.equals(lastCursor)) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
        if (!visited.add(next)) {
            throw new IOException("GitHub alert pagination repeated a page or cursor.");
        }
        current = next;
        return path + "?state=open&per_page=100&" + next;
    }

    private void requireMode(Mode requested) throws IOException {
        if (mode != null && mode != requested) {
            throw invalid();
        }
        mode = requested;
    }

    private String selector(String target, boolean allowInitialPage) throws IOException {
        try {
            URI uri = target.startsWith("?") ? URI.create(endpoint + target) : endpoint.resolve(target);
            if (!endpoint.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !endpoint.getHost().equalsIgnoreCase(uri.getHost())
                    || port(endpoint) != port(uri)
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || (!endpoint.getRawPath().equals(uri.getRawPath())
                            && (canonicalEndpoint == null
                                    || !canonicalEndpoint.getRawPath().equals(uri.getRawPath())))) {
                throw invalid();
            }
            String query = uri.getRawQuery();
            if (query == null || query.isEmpty()) {
                throw invalid();
            }
            Map<String, String> parameters = new HashMap<>();
            for (String parameter : query.split("&", -1)) {
                int separator = parameter.indexOf('=');
                if (separator <= 0) {
                    throw invalid();
                }
                String name = URLDecoder.decode(parameter.substring(0, separator), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(parameter.substring(separator + 1), StandardCharsets.UTF_8);
                if (!Set.of("state", "per_page", "page", "after", "before").contains(name)
                        || value.isBlank()
                        || value.indexOf('\uFFFD') >= 0
                        || value.codePoints().anyMatch(Character::isISOControl)
                        || parameters.putIfAbsent(name, value) != null) {
                    throw invalid();
                }
            }
            if (!"open".equals(parameters.getOrDefault("state", "open"))
                    || !"100".equals(parameters.getOrDefault("per_page", "100"))) {
                throw invalid();
            }
            String selector = null;
            for (String name : new String[] {"page", "after", "before"}) {
                if (!parameters.containsKey(name)) {
                    continue;
                }
                if (selector != null) {
                    throw invalid();
                }
                String value = parameters.get(name);
                selector = name.equals("page")
                        ? "page=" + pageNumber(value)
                        : name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
            }
            if (selector == null) {
                if (!allowInitialPage) {
                    throw invalid();
                }
                return "page=1";
            }
            return selector;
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private static int pageNumber(String value) throws IOException {
        if (!value.matches("[1-9][0-9]{0,9}")) {
            throw invalid();
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static IOException invalid() {
        return new IOException("GitHub returned invalid or unsupported alert pagination.");
    }

    private enum Mode {
        NUMBERED,
        CURSOR
    }
}
