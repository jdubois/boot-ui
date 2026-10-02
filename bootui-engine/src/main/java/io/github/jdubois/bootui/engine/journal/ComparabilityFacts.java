package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The facts that decide whether two runs can be compared ({@code docs/PLAN-v2.md} §5.8, §5.18): runs on different
 * profiles, databases, or caches are not comparable, and runs that recorded different journal sources or tracing are
 * compared with that limitation. Data sources are kept by name and the shape of their URL, such as
 * {@code jdbc:postgresql://localhost} or {@code jdbc:h2:mem}, never a database name, credentials, or parameters.
 *
 * @param activeProfiles the active profiles, sorted
 * @param dataSources each data source's URL shape, by data source name, sorted by name
 * @param cacheType the cache in use, such as {@code CaffeineCacheManager}, or {@code none}
 * @param tracing whether tracing is on
 * @param journalSources the journal's sources, as {@code bootui.runtime-journal.sources} names them, sorted
 */
public record ComparabilityFacts(
        List<String> activeProfiles,
        Map<String, String> dataSources,
        String cacheType,
        boolean tracing,
        List<String> journalSources) {

    private static final Set<String> EMBEDDED = Set.of("h2", "hsqldb", "derby");

    public ComparabilityFacts {
        activeProfiles = activeProfiles == null
                ? List.of()
                : activeProfiles.stream().filter(Objects::nonNull).sorted().toList();
        dataSources = dataSources == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(dataSources)));
        cacheType = cacheType == null || cacheType.isBlank() ? "none" : cacheType;
        journalSources = journalSources == null
                ? List.of()
                : journalSources.stream().filter(Objects::nonNull).sorted().toList();
    }

    /**
     * The facts of a run whose data sources have the given raw JDBC URLs, by name: each URL is reduced to its
     * {@linkplain #urlShape shape} here, so no raw URL is kept.
     */
    public static ComparabilityFacts of(
            List<String> activeProfiles,
            Map<String, String> jdbcUrls,
            String cacheType,
            boolean tracing,
            Set<JournalSource> journalSources) {
        Map<String, String> shapes = new TreeMap<>();
        if (jdbcUrls != null) {
            jdbcUrls.forEach((name, url) -> {
                if (name != null) {
                    shapes.put(name, urlShape(url));
                }
            });
        }
        List<String> sources = journalSources == null
                ? List.of()
                : journalSources.stream().map(JournalSource::propertyName).toList();
        return new ComparabilityFacts(activeProfiles, shapes, cacheType, tracing, sources);
    }

    /**
     * The shape of a data source URL: its protocol and vendor, then {@code mem} or {@code file} for an embedded
     * database, or its host for a server, such as {@code jdbc:postgresql://db.internal}. Credentials, ports, database
     * names, and parameters are dropped.
     */
    public static String urlShape(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        String lower = url.strip().toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon <= 0) {
            return "unknown";
        }
        String protocol = lower.substring(0, colon);
        String rest = lower.substring(colon + 1);
        String vendor = token(rest);
        rest = rest.substring(vendor.length());
        if (vendor.equals("tc") && rest.startsWith(":")) {
            String container = token(rest.substring(1));
            vendor = "tc:" + container;
            rest = rest.substring(1 + container.length());
        }
        StringBuilder shape = new StringBuilder(protocol).append(':').append(vendor);
        String host = host(rest);
        if (EMBEDDED.contains(vendor) && host == null) {
            String mode = rest.startsWith(":") ? token(rest.substring(1)) : "";
            shape.append(mode.equals("mem") || mode.equals("memory") ? ":mem" : ":file");
        } else if (host != null) {
            shape.append("://").append(host);
        }
        return shape.toString();
    }

    /**
     * Why a run with these facts is not comparable with a run with {@code previous}, data sources first, or an empty
     * list when it is.
     */
    public List<String> notComparableReasons(ComparabilityFacts previous) {
        List<String> reasons = new ArrayList<>();
        if (!dataSources.equals(previous.dataSources)) {
            reasons.add("The data sources differ: " + describe(previous.dataSources) + " before, "
                    + describe(dataSources) + " now.");
        }
        if (!activeProfiles.equals(previous.activeProfiles)) {
            reasons.add("The active profiles differ: " + list(previous.activeProfiles) + " before, "
                    + list(activeProfiles) + " now.");
        }
        if (!cacheType.equals(previous.cacheType)) {
            reasons.add("The cache differs: " + previous.cacheType + " before, " + cacheType + " now.");
        }
        return reasons;
    }

    /** What limits a comparison with a run with {@code previous}, which is otherwise comparable. */
    public List<String> limitations(ComparabilityFacts previous) {
        List<String> limitations = new ArrayList<>();
        if (tracing != previous.tracing) {
            limitations.add("Tracing was " + (previous.tracing ? "on" : "off") + " before and is "
                    + (tracing ? "on" : "off") + " now, so links by trace id differ.");
        }
        if (!journalSources.equals(previous.journalSources)) {
            limitations.add("The journal recorded " + list(previous.journalSources) + " before and "
                    + list(journalSources) + " now, so a fact only one run recorded is not compared.");
        }
        return limitations;
    }

    int estimatedBytes() {
        int bytes = 64 + RuntimeEvent.stringBytes(cacheType);
        for (String profile : activeProfiles) {
            bytes += 8 + RuntimeEvent.stringBytes(profile);
        }
        for (Map.Entry<String, String> dataSource : dataSources.entrySet()) {
            bytes += 32
                    + RuntimeEvent.stringBytes(dataSource.getKey())
                    + RuntimeEvent.stringBytes(dataSource.getValue());
        }
        return bytes + 8 * journalSources.size();
    }

    private static String token(String value) {
        int end = 0;
        while (end < value.length() && ":/;?@".indexOf(value.charAt(end)) < 0) {
            end++;
        }
        return value.substring(0, end);
    }

    /** The host after {@code //}, without credentials or port, or {@code null} when there is none. */
    private static String host(String rest) {
        int slashes = rest.indexOf("//");
        if (slashes < 0) {
            return null;
        }
        String authority = rest.substring(slashes + 2);
        int end = 0;
        while (end < authority.length() && "/;?,".indexOf(authority.charAt(end)) < 0) {
            end++;
        }
        authority = authority.substring(0, end);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        int port = authority.startsWith("[") ? authority.indexOf("]:") + 1 : authority.indexOf(':');
        if (port > 0) {
            authority = authority.substring(0, port);
        }
        return authority.isEmpty() ? null : authority;
    }

    private static String describe(Map<String, String> dataSources) {
        if (dataSources.isEmpty()) {
            return "none";
        }
        List<String> described = new ArrayList<>();
        dataSources.forEach((name, shape) -> described.add(name + " " + shape));
        return String.join(", ", described);
    }

    private static String list(List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }
}
