package io.github.jdubois.bootui.quarkus.devservices;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured Dev Services metadata into a runtime
 * {@link QuarkusDevServices} holder.
 *
 * <p>The deployment processor's {@code registerDevServices} build step consumes every
 * {@code DevServicesResultBuildItem} at build time, builds the {@link RawDevService} list, and calls
 * {@link #create(List)} from a {@code @Record(STATIC_INIT)} step; the returned {@link RuntimeValue} backs a
 * synthetic {@code QuarkusDevServices} bean. This is the same build-time-capture strategy the Scheduled
 * Tasks, Architecture and Vulnerabilities panels use, chosen here because Quarkus exposes Dev Services only
 * at build time (no runtime container-listing API), and routing the config maps through config keys would
 * corrupt JDBC URLs (commas/colons) and risk SmallRye {@code ${...}} expansion.</p>
 */
@Recorder
public class DevServicesRecorder {

    /** Wraps the captured rows in a runtime holder backing the synthetic {@link QuarkusDevServices} bean. */
    public RuntimeValue<QuarkusDevServices> create(List<RawDevService> services) {
        return new RuntimeValue<>(new QuarkusDevServices(services));
    }
}
