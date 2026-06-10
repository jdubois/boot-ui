package io.github.jdubois.bootui.autoconfigure.sourcetree;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The build system of the host application the BootUI starter is installed in, detected from the
 * files in the project root, together with the shared pieces the generated Dockerfiles need: the
 * pinned Maven/Gradle releases and the in-image setup step that either makes the build-tool wrapper
 * executable or installs a pinned build tool.
 *
 * <p>This is shared by every panel that scaffolds a single-module build Dockerfile (the GraalVM
 * {@code Dockerfile-native} and the CRaC {@code Dockerfile-crac}). Each panel keeps only its
 * feature-specific bits (the base images, the build command and the output directory) and supplies
 * the {@link DockerPackageManager} that matches its base image, so the Maven/Gradle versions, the
 * detection rules and the download/extract/symlink logic live in exactly one place.
 */
public enum ProjectBuildSystem {

    /** Maven build driven through the project's {@code mvnw} wrapper. */
    MAVEN_WRAPPER,
    /** Maven build with no wrapper; a pinned Maven release is installed in the build image. */
    MAVEN,
    /** Gradle build driven through the project's {@code gradlew} wrapper. */
    GRADLE_WRAPPER,
    /** Gradle build with no wrapper; a pinned Gradle release is installed in the build image. */
    GRADLE;

    /** Maven release installed in the build stage when the project carries no Maven Wrapper. */
    public static final String MAVEN_VERSION = "3.9.16";

    /** Gradle release installed in the build stage when the project carries no Gradle Wrapper. */
    public static final String GRADLE_VERSION = "9.5.1";

    /** Whether this build system runs through a build-tool wrapper checked into the project. */
    public boolean hasWrapper() {
        return this == MAVEN_WRAPPER || this == GRADLE_WRAPPER;
    }

    /** Whether this build system is Gradle (wrapper or not). */
    public boolean usesGradle() {
        return this == GRADLE || this == GRADLE_WRAPPER;
    }

    /** Whether this build system is Maven (wrapper or not). */
    public boolean usesMaven() {
        return !usesGradle();
    }

    /**
     * Detects the build system from the files in the project root. Prefers the wrapper when its
     * script is present; falls back to the Maven Wrapper layout when nothing is recognised (the
     * historical default) and prefers Maven on the rare projects that carry both build systems.
     */
    public static ProjectBuildSystem detect(@Nullable Path projectRoot) {
        if (projectRoot == null) {
            return MAVEN_WRAPPER;
        }
        boolean mavenWrapper = isRegularFile(projectRoot, "mvnw");
        boolean maven = mavenWrapper || isRegularFile(projectRoot, "pom.xml");
        boolean gradleWrapper = isRegularFile(projectRoot, "gradlew");
        boolean gradle = gradleWrapper
                || isRegularFile(projectRoot, "build.gradle")
                || isRegularFile(projectRoot, "build.gradle.kts")
                || isRegularFile(projectRoot, "settings.gradle")
                || isRegularFile(projectRoot, "settings.gradle.kts");
        if (gradle && !maven) {
            return gradleWrapper ? GRADLE_WRAPPER : GRADLE;
        }
        if (maven) {
            return mavenWrapper ? MAVEN_WRAPPER : MAVEN;
        }
        return MAVEN_WRAPPER;
    }

    /**
     * Returns the Dockerfile build-stage setup step for this build system: make the wrapper
     * executable, or download and install the pinned build tool with the supplied package manager
     * (chosen by the caller to match its base image). The result is meant to be substituted into the
     * generated Dockerfile right after the {@code COPY} of the project sources.
     */
    public String dockerSetup(DockerPackageManager packageManager) {
        return switch (this) {
            case MAVEN_WRAPPER -> """
                    # Make the Maven Wrapper executable.
                    RUN chmod +x mvnw""";
            case GRADLE_WRAPPER -> """
                    # Make the Gradle Wrapper executable.
                    RUN chmod +x gradlew""";
            case MAVEN -> """
                    # Maven is not bundled in the base image and the project has no Maven Wrapper, so
                    # install a known Maven release. It builds with the image's JDK via JAVA_HOME.
                    ARG MAVEN_VERSION=%1$s
                    RUN %2$s && \\
                        curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tar.gz && \\
                        tar -xzf /tmp/maven.tar.gz -C /opt && rm /tmp/maven.tar.gz && \\
                        ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn""".formatted(MAVEN_VERSION, packageManager.installTarballTools());
            case GRADLE -> """
                    # Gradle is not bundled in the base image and the project has no Gradle Wrapper, so
                    # install a known Gradle release. It builds with the image's JDK via JAVA_HOME.
                    ARG GRADLE_VERSION=%1$s
                    RUN %2$s && \\
                        curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip && \\
                        unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip && \\
                        ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle""".formatted(GRADLE_VERSION, packageManager.installZipTools());
        };
    }

    private static boolean isRegularFile(Path projectRoot, String name) {
        try {
            return Files.isRegularFile(projectRoot.resolve(name));
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
