package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves a concrete request path to the route template the application declared for it, using the route
 * mappings BootUI already reports in the Mappings panel.
 *
 * <p>This exists so route attribution can group by a declared template even where the request-capture point
 * cannot supply one. Spring MVC and Spring WebFlux hand BootUI the best-matching pattern directly; Quarkus
 * has no equivalent runtime hook, and without this resolver every Quarkus route would fall back to a masked
 * path, where a path-parameter value that happens to read like a word — a slug, a status, a username — is
 * lexically indistinguishable from a fixed route segment and would be shown as one.</p>
 *
 * <p>Matching is deliberately conservative. A template wins only when it is the single best match for the
 * path: same segment count, every literal segment equal, and strictly more literal segments than any other
 * candidate. A tie resolves to no template at all, because two declarations that both match are two
 * plausible groupings and BootUI does not pick between them. The declared templates are the application's
 * own route definitions, so nothing here can introduce a value the panel would refuse to display.</p>
 */
public final class RouteTemplateResolver {

    /** Templates indexed, bounding the work a pathological mapping table can cause. */
    static final int MAX_TEMPLATES = 2_000;

    /** Segments compared, matching the masker's own depth bound. */
    private static final int MAX_SEGMENTS = 12;

    private final Supplier<List<String[]>> loader;

    private List<String[]> templates;

    private RouteTemplateResolver(List<String[]> templates) {
        this.templates = templates;
        this.loader = null;
    }

    private RouteTemplateResolver(Supplier<List<String[]>> loader) {
        this.loader = loader;
    }

    /** An empty resolver, which never resolves anything. */
    public static RouteTemplateResolver empty() {
        return new RouteTemplateResolver(List.of());
    }

    /** Indexes the declared patterns of {@code mappings}, ignoring the HTTP method. */
    public static RouteTemplateResolver of(List<MappingDto> mappings) {
        return new RouteTemplateResolver(index(mappings));
    }

    /**
     * A resolver that reads {@code mappings} only when a path first needs resolving, so a caller whose requests
     * all carry a framework template never enumerates the application's routes. A supplier that fails resolves
     * nothing, like {@link #empty()}. Meant for one request's work: the first read is not synchronized.
     */
    public static RouteTemplateResolver lazy(Supplier<List<MappingDto>> mappings) {
        return new RouteTemplateResolver(() -> {
            try {
                return index(mappings.get());
            } catch (RuntimeException ex) {
                return List.of();
            }
        });
    }

    private static List<String[]> index(List<MappingDto> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        Set<String> patterns = new LinkedHashSet<>();
        for (MappingDto mapping : mappings) {
            String pattern = mapping == null ? null : mapping.pattern();
            if (pattern != null && !pattern.isBlank() && patterns.size() < MAX_TEMPLATES) {
                patterns.add(pattern.trim());
            }
        }
        List<String[]> indexed = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            String[] segments = segments(pattern);
            if (segments.length > 0 && segments.length <= MAX_SEGMENTS) {
                indexed.add(segments);
            }
        }
        return List.copyOf(indexed);
    }

    private List<String[]> templates() {
        if (templates == null) {
            templates = loader.get();
        }
        return templates;
    }

    /** Whether any template was indexed, so callers can report the tier honestly. */
    public boolean isEmpty() {
        return templates().isEmpty();
    }

    /**
     * The declared template matching {@code path}, or {@code null} when none does or several do equally
     * well.
     */
    public String resolve(String path) {
        if (path == null || path.isBlank() || templates().isEmpty()) {
            return null;
        }
        String[] actual = segments(path);
        if (actual.length == 0 || actual.length > MAX_SEGMENTS) {
            return null;
        }
        String[] best = null;
        int bestLiterals = -1;
        boolean tied = false;
        for (String[] candidate : templates()) {
            if (candidate.length != actual.length) {
                continue;
            }
            int literals = literalMatches(candidate, actual);
            if (literals < 0) {
                continue;
            }
            if (literals > bestLiterals) {
                best = candidate;
                bestLiterals = literals;
                tied = false;
            } else if (literals == bestLiterals) {
                tied = true;
            }
        }
        return best == null || tied ? null : render(best);
    }

    /**
     * The number of literal segments that matched, or {@code -1} when the template does not match at all.
     * A template segment is a parameter when it is brace-delimited or a bare wildcard.
     */
    private static int literalMatches(String[] template, String[] actual) {
        int literals = 0;
        for (int i = 0; i < template.length; i++) {
            String segment = template[i];
            if (isParameter(segment)) {
                continue;
            }
            if (!segment.equals(actual[i])) {
                return -1;
            }
            literals++;
        }
        return literals;
    }

    private static boolean isParameter(String segment) {
        return (segment.startsWith("{") && segment.endsWith("}")) || segment.contains("*");
    }

    /**
     * The template as a display string, with every parameter segment rewritten to a plain {@code {value}}
     * placeholder when it carries a regular expression, so a declaration such as
     * {@code {id:[0-9]+}} never puts a pattern on screen.
     */
    private static String render(String[] template) {
        StringBuilder out = new StringBuilder();
        for (String segment : template) {
            out.append('/');
            if (isParameter(segment)) {
                out.append(simplifyParameter(segment));
            } else {
                out.append(segment);
            }
        }
        return out.toString();
    }

    private static String simplifyParameter(String segment) {
        if (!segment.startsWith("{") || !segment.endsWith("}")) {
            return RoutePathMasker.PLACEHOLDER;
        }
        String inner = segment.substring(1, segment.length() - 1);
        int colon = inner.indexOf(':');
        String name = colon < 0 ? inner : inner.substring(0, colon);
        return name.isBlank() ? RoutePathMasker.PLACEHOLDER : "{" + name.trim() + "}";
    }

    private static String[] segments(String path) {
        String withoutQuery = path.trim();
        int cut = withoutQuery.length();
        int query = withoutQuery.indexOf('?');
        if (query >= 0) {
            cut = query;
        }
        int fragment = withoutQuery.indexOf('#');
        if (fragment >= 0 && fragment < cut) {
            cut = fragment;
        }
        String[] raw = withoutQuery.substring(0, cut).split("/", -1);
        List<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments.toArray(new String[0]);
    }
}
