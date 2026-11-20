package io.github.jdubois.bootui.core.dto;

/**
 * Readiness signal for one third-party dependency on the classpath: whether it ships GraalVM
 * reachability metadata ({@code META-INF/native-image/}) and whether Oracle's GraalVM reachability
 * metadata repository has an entry tested for the detected dependency version. Spring AOT factories
 * resource presence is a separate contribution source, not proof of working native-image hints.
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
        String repositoryMetadataUrl,
        Boolean shipsSpringAotFactories) {

    public GraalVmDependencyDto {
        shipsSpringAotFactories = Boolean.TRUE.equals(shipsSpringAotFactories);
    }

    public GraalVmDependencyDto(
            String name,
            boolean shipsMetadata,
            String note,
            String coordinates,
            boolean repositoryMetadata,
            String repositoryMetadataVersion,
            String repositoryTestedVersions,
            String repositoryUrl,
            String repositoryMetadataUrl) {
        this(
                name,
                shipsMetadata,
                note,
                coordinates,
                repositoryMetadata,
                repositoryMetadataVersion,
                repositoryTestedVersions,
                repositoryUrl,
                repositoryMetadataUrl,
                false);
    }

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
        this(
                name,
                shipsMetadata,
                note,
                coordinates,
                repositoryMetadata,
                repositoryMetadataVersion,
                repositoryTestedVersions,
                null,
                null);
    }
}
