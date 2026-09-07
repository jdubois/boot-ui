package io.github.jdubois.bootui.engine.security;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounded configuration analysis of one CSP policy, not a browser or a response-header evaluator.
 * Callers must independently establish enforcement, header scope and writer precedence.
 */
public final class CspPolicy {

    private static final int MAX_POLICY_LENGTH = 8192;
    private static final int MAX_DIRECTIVES = 64;
    private static final int MAX_TOKENS = 256;
    private static final Pattern DIRECTIVE_NAME = Pattern.compile("[a-zA-Z0-9-]+");
    private static final Pattern NONCE_OR_HASH =
            Pattern.compile("'(?:nonce|sha256|sha384|sha512)-[a-zA-Z0-9+/_-]+={0,2}'", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*:");
    private static final Pattern ANY_HOST = Pattern.compile("(?:[a-z][a-z0-9+.-]*://)?\\*(?::(?:[0-9]+|\\*))?(?:/.*)?");
    private static final Pattern HOST_SOURCE = Pattern.compile(
            "(?:[a-z][a-z0-9+.-]*://)?(?:\\*\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*(?::(?:[0-9]+|\\*))?(?:/[^\\s]*)?");
    private static final Analysis UNKNOWN = new Analysis(false, false, false, false, false, false);

    private CspPolicy() {}

    /**
     * An incomplete result must not be used as proof of a missing or weak control. The result
     * deliberately retains no policy text, nonces, hashes, hosts or other configuration values.
     */
    public record Analysis(
            boolean complete,
            boolean unsafeInlineScript,
            boolean unsafeEvalScript,
            boolean unrestrictedScript,
            boolean restrictiveFrameAncestors,
            boolean frameAncestorsPresent) {}

    public static Analysis analyze(String policy) {
        if (policy == null || policy.length() > MAX_POLICY_LENGTH || policy.indexOf(',') >= 0) {
            return UNKNOWN;
        }
        for (int i = 0; i < policy.length(); i++) {
            char character = policy.charAt(i);
            if (character >= 127 || (character < 32 && "\t\n\r\f".indexOf(character) < 0)) {
                return UNKNOWN;
            }
        }
        String[] parts = policy.split(";", -1);
        if (parts.length > MAX_DIRECTIVES) {
            return UNKNOWN;
        }
        Map<String, List<String>> directives = new LinkedHashMap<>();
        int tokenCount = 0;
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] tokens = trimmed.split("[\\t\\n\\r\\f ]+");
            tokenCount += tokens.length;
            if (tokenCount > MAX_TOKENS || !DIRECTIVE_NAME.matcher(tokens[0]).matches()) {
                return UNKNOWN;
            }
            String name = tokens[0].toLowerCase(Locale.ROOT);
            // CSP ignores later declarations of the same directive; they do not override the first.
            directives.putIfAbsent(name, List.copyOf(Arrays.asList(tokens).subList(1, tokens.length)));
        }

        List<String> scripts = fallback(directives, "script-src", "default-src");
        List<String> elements = directives.getOrDefault("script-src-elem", scripts);
        List<String> attributes = directives.getOrDefault("script-src-attr", scripts);
        List<String> ancestors = directives.get("frame-ancestors");
        boolean scriptsBlocked =
                directives.containsKey("sandbox") && !contains(directives.get("sandbox"), "allow-scripts");
        return new Analysis(
                true,
                !scriptsBlocked && (allowsInline(elements) || allowsInline(attributes)),
                !scriptsBlocked && contains(scripts, "'unsafe-eval'"),
                !scriptsBlocked
                        && !contains(elements, "'strict-dynamic'")
                        && elements.stream().anyMatch(CspPolicy::arbitrarySource),
                restrictiveAncestors(ancestors),
                ancestors != null);
    }

    private static List<String> fallback(Map<String, List<String>> directives, String primary, String secondary) {
        return directives.getOrDefault(primary, directives.getOrDefault(secondary, List.of()));
    }

    private static boolean contains(List<String> sources, String keyword) {
        return sources.stream().anyMatch(keyword::equalsIgnoreCase);
    }

    private static boolean allowsInline(List<String> sources) {
        return contains(sources, "'unsafe-inline'")
                && !contains(sources, "'strict-dynamic'")
                && sources.stream()
                        .noneMatch(source -> NONCE_OR_HASH.matcher(source).matches());
    }

    private static boolean arbitrarySource(String source) {
        String normalized = source.toLowerCase(Locale.ROOT);
        return SCHEME.matcher(normalized).matches()
                || ANY_HOST.matcher(normalized).matches();
    }

    private static boolean restrictiveAncestors(List<String> sources) {
        if (sources == null) {
            return false;
        }
        if (sources.isEmpty()) {
            return true;
        }
        if (sources.size() == 1 && contains(sources, "'none'")) {
            return true;
        }
        boolean recognized = false;
        for (String source : sources) {
            String normalized = source.toLowerCase(Locale.ROOT);
            if (arbitrarySource(normalized)) {
                return false;
            }
            if ("'self'".equals(normalized) || HOST_SOURCE.matcher(normalized).matches()) {
                recognized = true;
            } else if (!"'none'".equals(normalized)) {
                return false;
            }
        }
        return recognized;
    }
}
