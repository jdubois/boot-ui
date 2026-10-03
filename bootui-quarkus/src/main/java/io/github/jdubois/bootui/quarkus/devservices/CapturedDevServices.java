package io.github.jdubois.bootui.quarkus.devservices;

import java.util.Optional;

/**
 * Snapshot of the Dev Services that Quarkus started for this application, published at static init by
 * {@link DevServicesRecorder#capture}.
 *
 * <p>The snapshot is deliberately not a synthetic CDI bean. A synthetic bean built from
 * {@code DevServicesResultBuildItem} makes Dev Services a prerequisite of the bean container, but logging
 * setup (which Compose and datasource Dev Services wait for) already depends on the bean container when an
 * extension such as OpenTelemetry logging installs a CDI-backed log handler. That closes a build-step cycle
 * and Quarkus refuses to start. A recorder that only writes this holder adds no bean-container input.</p>
 *
 * <p>Extension classes can outlive one start: dev mode keeps dependency jars in a class loader that survives
 * live reload. Every dev or test start therefore replays the recorder, which replaces the snapshot or clears it
 * when no Dev Service started. It is never written in production (the build step skips
 * {@code LaunchMode.NORMAL}). An empty holder means the Dev Services panel is unavailable.</p>
 */
public final class CapturedDevServices {

    private static volatile QuarkusDevServices captured;

    private CapturedDevServices() {}

    /** The captured Dev Services, or empty when none were started. */
    public static Optional<QuarkusDevServices> current() {
        return Optional.ofNullable(captured);
    }

    static void publish(QuarkusDevServices services) {
        captured = services;
    }
}
