package io.github.jdubois.bootui.engine.mappings;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Framework-neutral logic behind the Mappings panel's {@code /flat} view, shared by the Spring Boot and
 * Quarkus adapters.
 *
 * <p>It reads the already-flattened, already-self-filtered mappings from a {@link MappingProvider}
 * (optional: {@code null} when the backend type is absent) and applies BootUI's stable ordering,
 * free-text query and server-side paging on top. The flattening and self-data filtering deliberately
 * stay in the provider (the adapter): the raw mapping predicate string that the self-data filter
 * inspects is lost once a row becomes a {@link MappingDto}, so filtering there is provably
 * byte-identical to the original Spring controller.</p>
 */
public final class MappingsService {

    private final MappingProvider provider;

    public MappingsService(MappingProvider provider) {
        this.provider = provider;
    }

    /** The sorted, queried and paged mappings report; empty when no backend is available. */
    public MappingsReport report(String query, Integer offset, Integer limit) {
        if (provider == null || !provider.available()) {
            return new MappingsReport(0, List.of());
        }
        String basePath = composeBasePath(provider.basePath());
        List<MappingDto> mappings = new ArrayList<>(provider.mappings());
        Comparator<String> nullSafeString = Comparator.nullsLast(String::compareTo);
        mappings.sort(Comparator.comparing(MappingDto::pattern, nullSafeString)
                .thenComparing(MappingDto::method, nullSafeString)
                .thenComparing(MappingDto::handler, nullSafeString));
        String normalizedQuery = PagedList.normalize(query);
        PagedList.Result<MappingDto> page =
                PagedList.from(mappings, mapping -> matchesQuery(mapping, normalizedQuery), offset, limit);
        return new MappingsReport(mappings.size(), basePath, page.items(), page.page());
    }

    /**
     * Normalizes and joins one or more configured base-path segments (such as
     * {@code server.servlet.context-path} and {@code spring.mvc.servlet.path},
     * {@code spring.webflux.base-path}, or {@code quarkus.http.root-path} and
     * {@code quarkus.rest.path}) into a single leading-slash prefix with no trailing slash, or
     * {@code ""} when the effective mount is {@code /}.
     */
    public static String composeBasePath(String... segments) {
        if (segments == null || segments.length == 0) {
            return "";
        }
        StringBuilder composed = new StringBuilder();
        for (String segment : segments) {
            String normalized = normalizePrefix(segment);
            if (!normalized.isEmpty()) {
                composed.append(normalized);
            }
        }
        return composed.toString();
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("*")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return "";
        }
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        StringBuilder collapsed = new StringBuilder(trimmed.length());
        boolean previousSlash = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '/') {
                if (!previousSlash) {
                    collapsed.append(ch);
                }
                previousSlash = true;
            } else {
                collapsed.append(ch);
                previousSlash = false;
            }
        }
        while (collapsed.length() > 1 && collapsed.charAt(collapsed.length() - 1) == '/') {
            collapsed.setLength(collapsed.length() - 1);
        }
        return "/".equals(collapsed.toString()) ? "" : collapsed.toString();
    }

    private static boolean matchesQuery(MappingDto mapping, String query) {
        return PagedList.contains(mapping.method(), query)
                || PagedList.contains(mapping.pattern(), query)
                || PagedList.contains(mapping.handler(), query)
                || PagedList.contains(mapping.produces(), query)
                || PagedList.contains(mapping.consumes(), query);
    }
}
