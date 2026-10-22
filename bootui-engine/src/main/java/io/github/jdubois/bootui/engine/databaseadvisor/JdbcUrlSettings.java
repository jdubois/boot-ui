package io.github.jdubois.bootui.engine.databaseadvisor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads allow-listed driver settings out of a JDBC URL and keeps nothing else.
 *
 * <p>The URL is secret-bearing ({@code user=} and {@code password=} may ride in its query, and an authority may
 * carry credentials), so this parser never retains, returns or logs any part of it that is not an allow-listed
 * setting. Names are compared case-insensitively and returned under the caller's spelling, values are
 * percent-decoded and capped (never trimmed, and a {@code #} is not a fragment: the drivers do not treat it as one),
 * the last occurrence of a name wins, and anything malformed yields an empty result rather than an exception. It is
 * deliberately tolerant of the vendor-specific URL shapes ({@code jdbc:mysql://h1:3306,h2:3306/db},
 * {@code jdbc:mysql:replication://}, {@code jdbc:mariadb://}) because it only looks after the first {@code ?}.</p>
 *
 * <p>{@link #parseDetailed} also reports what a flat map cannot say: which names were spelled other than canonically
 * or percent-escaped (neither driver honors those), and which names appear as Connector/J host-level properties
 * ({@code address=(host=h)(port=3306)(useAffectedRows=true)} or {@code (host=h,port=3306,useAffectedRows=true)}),
 * whose scope and precedence are not modeled.</p>
 */
final class JdbcUrlSettings {

    private static final String SCHEME = "jdbc:";

    private JdbcUrlSettings() {}

    /**
     * @param values the setting values by name, as the caller spelled the names
     * @param irregularSpelling names that appeared with a spelling other than the caller's
     * @param hostScoped names that appeared as host-level properties before the query, by name
     */
    record Parsed(Map<String, String> values, Set<String> irregularSpelling, Map<String, String> hostScoped) {

        static final Parsed EMPTY = new Parsed(Map.of(), Set.of(), Map.of());
    }

    static Map<String, String> parse(String jdbcUrl, Set<String> allowedNames) {
        return parseDetailed(jdbcUrl, allowedNames).values();
    }

    static Parsed parseDetailed(String jdbcUrl, Set<String> allowedNames) {
        if (jdbcUrl == null || allowedNames == null || allowedNames.isEmpty()) {
            return Parsed.EMPTY;
        }
        try {
            String url = jdbcUrl.trim();
            if (!url.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
                return Parsed.EMPTY;
            }
            Map<String, String> canonicalByLowerCase = new HashMap<>();
            for (String name : allowedNames) {
                canonicalByLowerCase.put(name.toLowerCase(Locale.ROOT), name);
            }
            int query = url.indexOf('?');
            Map<String, String> hostScoped =
                    hostScoped(query < 0 ? url : url.substring(0, query), canonicalByLowerCase);
            if (query < 0) {
                return new Parsed(Map.of(), Set.of(), hostScoped);
            }
            Map<String, String> settings = new LinkedHashMap<>();
            Set<String> irregular = new HashSet<>();
            for (String parameter : url.substring(query + 1).split("&")) {
                int equals = parameter.indexOf('=');
                // Neither driver decodes a name, so a percent-escaped spelling is not the setting: it is noticed (by
                // its
                // decoded form) and reported as irregular, never silently read as the canonical name.
                String rawName = equals < 0 ? parameter : parameter.substring(0, equals);
                String canonical = canonicalByLowerCase.get(decode(rawName).toLowerCase(Locale.ROOT));
                if (canonical != null) {
                    if (!canonical.equals(rawName)) {
                        irregular.add(canonical);
                    }
                    String value = equals < 0 ? "" : decode(parameter.substring(equals + 1));
                    settings.put(canonical, DriverSettingCatalog.bounded(value));
                }
            }
            return new Parsed(Map.copyOf(settings), Set.copyOf(irregular), hostScoped);
        } catch (RuntimeException ex) {
            return Parsed.EMPTY;
        }
    }

    /** {@code (name=value)} groups before the query, which Connector/J reads as properties of one host. */
    private static Map<String, String> hostScoped(String beforeQuery, Map<String, String> canonicalByLowerCase) {
        Map<String, String> scoped = new LinkedHashMap<>();
        int index = beforeQuery.indexOf('(');
        while (index >= 0) {
            int close = beforeQuery.indexOf(')', index);
            if (close < 0) {
                break;
            }
            // Both (name=value)(name=value) and the key-value form (name=value,name=value).
            for (String pair : beforeQuery.substring(index + 1, close).split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    String canonical = canonicalByLowerCase.get(
                            pair.substring(0, equals).trim().toLowerCase(Locale.ROOT));
                    if (canonical != null) {
                        scoped.put(canonical, DriverSettingCatalog.bounded(decode(pair.substring(equals + 1))));
                    }
                }
            }
            index = beforeQuery.indexOf('(', close);
        }
        return Map.copyOf(scoped);
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }
}
