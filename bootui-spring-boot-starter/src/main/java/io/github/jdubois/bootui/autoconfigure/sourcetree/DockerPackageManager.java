package io.github.jdubois.bootui.autoconfigure.sourcetree;

/**
 * The OS package manager of a Docker build image, used to install the small set of tools
 * ({@code curl} plus an archive extractor) needed to download a pinned Maven or Gradle release when
 * the host project carries no build-tool wrapper.
 *
 * <p>The two flavours exist because BootUI's generated Dockerfiles build on different base images:
 * the GraalVM {@code Dockerfile-native} builds on a UBI-based GraalVM image ({@link #MICRODNF}), while
 * the CRaC {@code Dockerfile-crac} builds on a Debian-based Temurin image ({@link #APT}). Debian ships
 * {@code tar}/{@code gzip}, so {@link #APT} only adds {@code ca-certificates} (and {@code unzip} for
 * Gradle), whereas the minimal UBI image must also install {@code tar}/{@code gzip} for a
 * {@code .tar.gz}.
 */
public enum DockerPackageManager {

    /** Debian/Ubuntu base images (for example {@code eclipse-temurin:*-noble}). */
    APT(
            "apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \\\n"
                    + "    rm -rf /var/lib/apt/lists/*",
            "apt-get update && apt-get install -y --no-install-recommends curl ca-certificates unzip && \\\n"
                    + "    rm -rf /var/lib/apt/lists/*"),

    /** UBI/minimal base images (for example {@code ghcr.io/graalvm/graalvm-community:*}). */
    MICRODNF(
            "microdnf install -y curl tar gzip && microdnf clean all",
            "microdnf install -y curl unzip && microdnf clean all");

    private final String tarballTools;
    private final String zipTools;

    DockerPackageManager(String tarballTools, String zipTools) {
        this.tarballTools = tarballTools;
        this.zipTools = zipTools;
    }

    /** Command that ensures {@code curl} and a {@code .tar.gz} extractor are available (Maven). */
    String installTarballTools() {
        return tarballTools;
    }

    /** Command that ensures {@code curl} and a {@code .zip} extractor are available (Gradle). */
    String installZipTools() {
        return zipTools;
    }
}
