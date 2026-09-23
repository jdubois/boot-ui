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
 * @param archivesUnidentified how many of them neither resolved to a coordinate nor were recognized as
 *     first-party, and are therefore an unscanned gap
 * @param unidentifiedArchives the unresolved archive file names, bounded for transport
 * @param unidentifiedArchivesTruncated whether {@code unidentifiedArchives} omits some names because the
 *     transport bound was hit
 * @param archivesFirstParty how many of them were recognized as the application's own code (for example the
 *     module JARs of a multi-module build, whose every class sits under the application's base packages).
 *     They carry no third-party coordinates, are not scannable, and do not count against coverage; always
 *     {@code archivesFound = archivesIdentified + archivesUnidentified + archivesFirstParty}
 * @param firstPartyArchives the first-party archive file names, bounded for transport
 * @param firstPartyArchivesTruncated whether {@code firstPartyArchives} omits some names because the
 *     transport bound was hit
 */
public record DependencyCoverageDto(
        String status,
        int archivesFound,
        int archivesIdentified,
        int archivesUnidentified,
        List<String> unidentifiedArchives,
        boolean unidentifiedArchivesTruncated,
        int archivesFirstParty,
        List<String> firstPartyArchives,
        boolean firstPartyArchivesTruncated) {

    /** Coverage could not be determined; the caller could not enumerate the application's archives. */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** Every enumerated archive resolved to a Maven coordinate or was recognized as first-party. */
    public static final String COMPLETE = "COMPLETE";

    /** At least one enumerated archive was neither resolved to a Maven coordinate nor recognized as first-party. */
    public static final String INCOMPLETE = "INCOMPLETE";

    public DependencyCoverageDto {
        unidentifiedArchives = DtoCollections.immutableCopy(unidentifiedArchives);
        firstPartyArchives = DtoCollections.immutableCopy(firstPartyArchives);
    }

    /**
     * Coverage for a provider that cannot enumerate the application's archives, so it can neither claim nor
     * deny full coverage.
     */
    public static DependencyCoverageDto unavailable() {
        return new DependencyCoverageDto(UNAVAILABLE, 0, 0, 0, List.of(), false, 0, List.of(), false);
    }

    /**
     * Coverage for a provider whose inventory is authoritative by construction &mdash; for example Quarkus,
     * which reads the fully-resolved build-time application model rather than probing the classpath.
     *
     * @param identified the number of resolved dependencies in the inventory
     */
    public static DependencyCoverageDto complete(int identified) {
        int count = Math.max(0, identified);
        return new DependencyCoverageDto(COMPLETE, count, count, 0, List.of(), false, 0, List.of(), false);
    }

    /**
     * Coverage derived from a completed archive census with no first-party archives. The status is
     * {@link #COMPLETE} only when no archive was left unidentified.
     *
     * @param archivesFound the number of archives enumerated
     * @param archivesUnidentified how many of them did not resolve to a Maven coordinate
     * @param unidentifiedArchives the unresolved archive file names, already bounded
     * @see #of(int, int, List, int, List)
     */
    public static DependencyCoverageDto of(
            int archivesFound, int archivesUnidentified, List<String> unidentifiedArchives) {
        return of(archivesFound, archivesUnidentified, unidentifiedArchives, 0, List.of());
    }

    /**
     * Coverage derived from a completed archive census. The status is {@link #COMPLETE} only when no archive
     * was left unidentified; first-party archives are the application itself and never count against it.
     *
     * <p>The name lists may be shorter than their counts because they are bounded for transport; the counts
     * are always the true totals, so a truncated list never under-reports the size of the gap.</p>
     *
     * @param archivesFound the number of archives enumerated
     * @param archivesUnidentified how many of them neither resolved to a Maven coordinate nor were first-party
     * @param unidentifiedArchives the unresolved archive file names, already bounded
     * @param archivesFirstParty how many of them were recognized as the application's own code
     * @param firstPartyArchives the first-party archive file names, already bounded
     */
    public static DependencyCoverageDto of(
            int archivesFound,
            int archivesUnidentified,
            List<String> unidentifiedArchives,
            int archivesFirstParty,
            List<String> firstPartyArchives) {
        int found = Math.max(0, archivesFound);
        int unidentified = Math.min(found, Math.max(0, archivesUnidentified));
        int firstParty = Math.min(found - unidentified, Math.max(0, archivesFirstParty));
        int listed = unidentifiedArchives == null ? 0 : unidentifiedArchives.size();
        int firstPartyListed = firstPartyArchives == null ? 0 : firstPartyArchives.size();
        return new DependencyCoverageDto(
                unidentified == 0 ? COMPLETE : INCOMPLETE,
                found,
                found - unidentified - firstParty,
                unidentified,
                unidentifiedArchives,
                listed < unidentified,
                firstParty,
                firstPartyArchives,
                firstPartyListed < firstParty);
    }
}
