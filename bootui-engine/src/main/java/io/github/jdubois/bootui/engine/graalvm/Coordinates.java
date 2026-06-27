package io.github.jdubois.bootui.engine.graalvm;

/**
 * Maven coordinates ({@code groupId:artifactId:version}) of an inspected classpath dependency. The
 * GraalVM dependency-metadata survey passes these to the {@link ReachabilityMetadataRepository} seam to
 * look up Oracle reachability-metadata repository coverage. {@code groupId} may be {@code null} when
 * only a jar name was available; the engine only performs a repository lookup once the coordinates are
 * {@link #isComplete() complete}.
 */
public record Coordinates(String groupId, String artifactId, String version) {

    boolean isComplete() {
        return groupId != null && artifactId != null && version != null;
    }

    String display() {
        if (groupId == null) {
            return artifactId + ":" + version;
        }
        return groupId + ":" + artifactId + ":" + version;
    }
}
