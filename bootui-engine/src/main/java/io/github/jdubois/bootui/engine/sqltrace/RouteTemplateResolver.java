package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
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
        return new RouteTemplateResolver(() -> load(mappings));
    }

    /**
     * A source of lazy resolvers that share one index: the first resolver to need the mappings reads and
     * indexes them, and every later resolver reuses that index instead of enumerating the application's routes
     * again. An application's route declarations do not change after startup, so a panel polled every few
     * seconds pays for the enumeration once. An empty or failed read is not cached, so a mappings source that
     * becomes available later is still picked up.
     */
    public static Supplier<RouteTemplateResolver> caching(Supplier<List<MappingDto>> mappings) {
        AtomicReference<List<String[]>> cache = new AtomicReference<>();
        return () -> {
            List<String[]> cached = cache.get();
            if (cached != null) {
                return new RouteTemplateResolver(cached);
            }
            return new RouteTemplateResolver(() -> {
                List<String[]> loaded = load(mappings);
                if (!loaded.isEmpty()) {
                    cache.set(loaded);
                }
                return loaded;
            });
        };
    }

    /**
     * {@code template} rendered exactly as a declared template would be, so a handler pattern the framework
     * reports and the same pattern resolved from the application's mappings produce one route. A parameter's
     * regular expression is dropped, as in {@code {id:[0-9]+?}} becoming {@code {id}}, so no pattern is ever
     * put on screen. A wildcard segment such as {@code **} is kept as declared: it is part of the application's
     * own route, and rendering it as {@code {value}} would merge a catch-all route with unrelated masked paths.
     */
    public static String canonical(String template) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String[] segments = templateSegments(template);
        return segments.length == 0 ? "/" : render(segments);
    }

    private static List<String[]> load(Supplier<List<MappingDto>> mappings) {
        try {
            return index(mappings.get());
        } catch (RuntimeException ex) {
            return List.of();
        }
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
            String[] segments = templateSegments(pattern);
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
     * The segment positions, counted from zero over the non-empty segments of {@code path}, that are a
     * parameter in <em>any</em> declared template matching it. When {@link #resolve} finds no single best
     * template because several declarations match equally well, these positions still hold values, so a masked
     * fallback masks them even when they read like route words, such as a user name.
     */
    public Set<Integer> parameterPositions(String path) {
        if (path == null || path.isBlank() || templates().isEmpty()) {
            return Set.of();
        }
        String[] actual = segments(path);
        if (actual.length == 0 || actual.length > MAX_SEGMENTS) {
            return Set.of();
        }
        Set<Integer> positions = new TreeSet<>();
        for (String[] candidate : templates()) {
            if (candidate.length != actual.length || literalMatches(candidate, actual) < 0) {
                continue;
            }
            for (int i = 0; i < candidate.length; i++) {
                if (isParameter(candidate[i])) {
                    positions.add(i);
                }
            }
        }
        return positions;
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
     * The template as a display string, with every brace-delimited variable rewritten to its bare name, so a
     * declaration such as {@code {id:[0-9]+}} or {@code report-{id:[0-9]+}.csv} never puts a pattern on screen.
     * Wildcards outside braces are kept as declared.
     */
    private static String render(String[] template) {
        StringBuilder out = new StringBuilder();
        for (String segment : template) {
            out.append('/').append(stripVariablePatterns(segment));
        }
        return out.toString();
    }

    /** Rewrites every top-level {@code {name:regex}} in {@code segment} to {@code {name}}. */
    private static String stripVariablePatterns(String segment) {
        StringBuilder out = new StringBuilder(segment.length());
        int depth = 0;
        StringBuilder variable = null;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '\\' && i + 1 < segment.length()) {
                // An escaped brace inside a variable's pattern is regex text, not template structure.
                (depth > 0 ? variable : out).append(c).append(segment.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    variable = new StringBuilder();
                } else {
                    variable.append(c);
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0) {
                    out.append(variableName(variable.toString()));
                    variable = null;
                } else {
                    variable.append(c);
                }
            } else if (depth > 0) {
                variable.append(c);
            } else {
                out.append(c);
            }
        }
        if (variable != null) {
            // An unbalanced brace is not a variable; keep what was declared rather than invent one.
            out.append('{').append(variable);
        }
        return out.toString();
    }

    private static String variableName(String inner) {
        int colon = inner.indexOf(':');
        String name = (colon < 0 ? inner : inner.substring(0, colon)).trim();
        return name.isEmpty() ? RoutePathMasker.PLACEHOLDER : "{" + name + "}";
    }

    /**
     * Splits a declared route pattern into its non-empty segments. Unlike an observed path, a pattern carries
     * no query string or fragment, and a variable's regular expression may itself contain {@code ?}, {@code #}
     * or {@code /}, so only a {@code /} outside braces separates segments.
     */
    private static String[] templateSegments(String template) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        String trimmed = template.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length()) {
                current.append(c).append(trimmed.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            }
            if (c == '/' && depth == 0) {
                if (current.length() > 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments.toArray(new String[0]);
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
