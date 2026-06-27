package io.github.jdubois.bootui.engine.graalvm;

/**
 * Static, restart-to-change settings for the GraalVM dependency-metadata survey: whether to look up
 * reachability metadata from the remote repository and the maximum number of dependency lookups.
 * Snapshotted from {@code bootui.graalvm.*} when the (lazy) engine scanner bean is created — these are
 * plain bound configuration BootUI never re-binds at runtime, so a value record (not a live policy
 * interface) is the right seam. The per-lookup HTTP timeout is a transport concern owned by the
 * {@link ReachabilityMetadataRepository} adapter implementation, not the engine.
 */
public record GraalVmDependencySettings(boolean repositoryLookupEnabled, int maxRepositoryLookups) {}
