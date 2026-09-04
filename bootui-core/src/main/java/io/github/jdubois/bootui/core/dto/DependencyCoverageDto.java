package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * How much of the running application's real JAR set the dependency inventory actually accounts for.
 *
 * <p>The inventory is coordinate-based: a JAR only enters it when a {@code groupId:artifactId:version} can
 * be resolved for it. Many widely-used artifacts ship no Maven descriptor at all (Spring Framework,
 * {@code tomcat-embed-*}, {@code hibernate-core}, {@code kotlin-stdlib}, the PostgreSQL driver, and others
 * are built without {@code META-INF/maven/.../pom.properties}), and no manifest header carries a groupId, so
 * without this record a repackaged application could report a green "0 vulnerable" summary while a large
 * part of its classpath was never scanned at all. This record makes that gap explicit instead.</p>
 *
 * @param status {@code COMPLETE} when every enumerated archive resolved to coordinates, {@code INCOMPLETE}
 *     when some did not, or {@code UNAVAILABLE} when the archive census itself could not run (an unknown or
 *     synthetic classpath, for example a native image) and coverage is therefore unknown rather than claimed
 * @param archivesFound the number of JAR archives enumerated from the running application
 * @param archivesIdentified how many of them resolved to a Maven coordinate and are in the inventory
 * @param archivesUnidentified how many of them did not, and are therefore not scannable
 * @param unidentifiedArchives the unresolved archive file names, bounded for transport
 * @param unidentifiedArchivesTruncated whether {@code unidentifiedArchives} omits some names because the
 *     transport bound was hit
 */
public record DependencyCoverageDto(
        String status,
        int archivesFound,
        int archivesIdentified,
        int archivesUnidentified,
        List<String> unidentifiedArchives,
        boolean unidentifiedArchivesTruncated) {

    /** Coverage could not be determined; the caller could not enumerate the application's archives. */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** Every enumerated archive resolved to a Maven coordinate. */
    public static final String COMPLETE = "COMPLETE";

    /** At least one enumerated archive could not be resolved to a Maven coordinate. */
    public static final String INCOMPLETE = "INCOMPLETE";

    public DependencyCoverageDto {
        unidentifiedArchives = DtoCollections.immutableCopy(unidentifiedArchives);
    }

    /**
     * Coverage for a provider that cannot enumerate the application's archives, so it can neither claim nor
     * deny full coverage.
     */
    public static DependencyCoverageDto unavailable() {
        return new DependencyCoverageDto(UNAVAILABLE, 0, 0, 0, List.of(), false);
    }

    /**
     * Coverage for a provider whose inventory is authoritative by construction &mdash; for example Quarkus,
     * which reads the fully-resolved build-time application model rather than probing the classpath.
     *
     * @param identified the number of resolved dependencies in the inventory
     */
    public static DependencyCoverageDto complete(int identified) {
        int count = Math.max(0, identified);
        return new DependencyCoverageDto(COMPLETE, count, count, 0, List.of(), false);
    }

    /**
     * Coverage derived from a completed archive census. The status is {@link #COMPLETE} only when no archive
     * was left unidentified.
     *
     * <p>{@code unidentifiedArchives} may be shorter than {@code archivesUnidentified} because the name list
     * is bounded for transport; the counts are always the true totals, so a truncated list never
     * under-reports the size of the gap.</p>
     *
     * @param archivesFound the number of archives enumerated
     * @param archivesUnidentified how many of them did not resolve to a Maven coordinate
     * @param unidentifiedArchives the unresolved archive file names, already bounded
     */
    public static DependencyCoverageDto of(
            int archivesFound, int archivesUnidentified, List<String> unidentifiedArchives) {
        int found = Math.max(0, archivesFound);
        int unidentified = Math.min(found, Math.max(0, archivesUnidentified));
        int listed = unidentifiedArchives == null ? 0 : unidentifiedArchives.size();
        return new DependencyCoverageDto(
                unidentified == 0 ? COMPLETE : INCOMPLETE,
                found,
                found - unidentified,
                unidentified,
                unidentifiedArchives,
                listed < unidentified);
    }
}
