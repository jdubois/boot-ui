package io.github.jdubois.bootui.quarkus.mappings;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured JAX-RS route mappings into a runtime
 * {@link QuarkusMappings} holder.
 *
 * <p>The deployment processor's {@code registerMappings} build step scans the build-time Jandex index for
 * {@code @Path} resource classes, builds the {@link RawMapping} list
 * (combining class- and method-level {@code @Path}, joining media types, rendering the handler, and dropping
 * BootUI's own {@code /bootui} routes), and calls {@link #create(List)} from a {@code @Record(STATIC_INIT)}
 * step; the returned {@link RuntimeValue} backs a synthetic {@link QuarkusMappings} bean. This is the same
 * build-time-capture strategy the Scheduled Tasks (annotation scan), Architecture (base packages) and
 * Vulnerabilities (dependency inventory) panels use, chosen here because no clean Quarkus runtime
 * route-enumeration API exposes the per-route method/produces/consumes detail this panel needs.</p>
 */
@Recorder
public class MappingsRecorder {

    /** Wraps the captured rows in a runtime holder backing the synthetic {@link QuarkusMappings} bean. */
    public RuntimeValue<QuarkusMappings> create(List<RawMapping> mappings) {
        return new RuntimeValue<>(new QuarkusMappings(mappings));
    }
}
