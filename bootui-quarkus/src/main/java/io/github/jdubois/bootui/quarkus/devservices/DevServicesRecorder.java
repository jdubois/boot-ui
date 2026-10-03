package io.github.jdubois.bootui.quarkus.devservices;

import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured Dev Services metadata into the runtime
 * {@link CapturedDevServices} snapshot.
 *
 * <p>The deployment processor's {@code captureDevServices} build step consumes every
 * {@code DevServicesResultBuildItem} at build time, builds the {@link RawDevService} list, and calls
 * {@link #capture(List)} from a {@code @Record(STATIC_INIT)} step. That step produces no bean-container input,
 * so Dev Services never become a prerequisite of ArC (see {@link CapturedDevServices} for the build-step cycle
 * this avoids). Build-time capture is needed because Quarkus exposes Dev Services only at build time (no
 * runtime container-listing API), and routing the config maps through config keys would corrupt JDBC URLs
 * (commas/colons) and risk SmallRye {@code ${...}} expansion.</p>
 */
@Recorder
public class DevServicesRecorder {

    /** Publishes the captured rows as this application's Dev Services snapshot; an empty list clears it. */
    public void capture(List<RawDevService> services) {
        CapturedDevServices.publish(services == null || services.isEmpty() ? null : new QuarkusDevServices(services));
    }
}
