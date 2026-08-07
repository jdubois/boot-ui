package io.github.jdubois.bootui.autoconfigure.spring;

import java.util.List;

/**
 * Small read-only value types describing the host application's wired beans, captured during a
 * single Spring Advisor scan.
 */
final class SpringModel {

    private SpringModel() {}

    /** A managed bean of interest and candidate metadata available in the read-only snapshot. */
    record BeanRef(
            String name, boolean primary, boolean autowireCandidate, boolean fallback, boolean defaultCandidate) {

        BeanRef(String name, boolean primary) {
            this(name, primary, true, false, true);
        }
    }

    /** A {@code CacheManager} bean: its name and the resolved implementation class name (may be null). */
    record CacheManagerRef(String name, String className) {}

    /** Counts the beans in {@code refs} that are marked primary. */
    static long primaryCount(List<BeanRef> refs) {
        return refs.stream().filter(BeanRef::primary).count();
    }

    /** Whether the primary, fallback, and default-candidate metadata resolves a single bean. */
    static boolean hasResolvedCandidateMetadata(List<BeanRef> refs) {
        List<BeanRef> candidates =
                refs.stream().filter(BeanRef::autowireCandidate).toList();
        if (candidates.size() < 2) {
            return true;
        }
        long primaryCandidates = primaryCount(candidates);
        if (primaryCandidates == 1) {
            return true;
        }
        if (primaryCandidates > 1) {
            return false;
        }
        long nonFallbackCandidates =
                candidates.stream().filter(ref -> !ref.fallback()).count();
        if (nonFallbackCandidates == 1) {
            return true;
        }
        return candidates.stream().filter(BeanRef::defaultCandidate).count() == 1;
    }

    /** Whether any bean in {@code refs} carries the conventional name {@code names}. */
    static boolean hasName(List<BeanRef> refs, String... names) {
        for (BeanRef ref : refs) {
            for (String name : names) {
                if (name.equals(ref.name())) {
                    return true;
                }
            }
        }
        return false;
    }
}
