package io.github.jdubois.bootui.core.dto;

/**
 * Readiness signal for one third-party dependency on the classpath: whether it ships GraalVM
 * reachability metadata ({@code META-INF/native-image/}) and whether Oracle's GraalVM reachability
 * metadata repository has an entry tested for the detected dependency version.
 */
public record GraalVmDependencyDto(
        String name,
        boolean shipsMetadata,
        String note,
        String coordinates,
        boolean repositoryMetadata,
        String repositoryMetadataVersion,
        String repositoryTestedVersions,
        String repositoryUrl,
        String repositoryMetadataUrl) {

    public GraalVmDependencyDto(String name, boolean shipsMetadata, String note) {
        this(name, shipsMetadata, note, null, false, null, null, null, null);
    }

    public GraalVmDependencyDto(
            String name,
            boolean shipsMetadata,
            String note,
            String coordinates,
            boolean repositoryMetadata,
            String repositoryMetadataVersion,
            String repositoryTestedVersions) {
        this(name, shipsMetadata, note, coordinates, repositoryMetadata, repositoryMetadataVersion, repositoryTestedVersions, null, null);
    }
}
