package io.github.jdubois.bootui.quarkus4.faulttolerance;

import io.quarkus.runtime.annotations.RecordableConstructor;

/**
 * One declared setting of a fault tolerance policy, captured verbatim at <em>build time</em> from a MicroProfile
 * Fault Tolerance annotation member and replayed into the runtime via a {@code @Recorder}.
 *
 * <p>Provenance is decided at build time by comparing the member's effective value with the specification's
 * documented default, because Jandex reports only the resolved value and the runtime has no access to the
 * annotation's source form.</p>
 *
 * <p>Serialized into the Quarkus bytecode recorder, so the canonical constructor is
 * {@link RecordableConstructor}; the module compiles with {@code -parameters} so parameter names match the
 * record components.</p>
 *
 * @param name the annotation member name, e.g. {@code failureRatio}
 * @param value the rendered value, e.g. {@code 0.5} or {@code 2000 ms}
 * @param provenance {@code DEFAULT}, {@code CONFIGURED} or {@code UNKNOWN}
 */
public record RawFaultToleranceSetting(String name, String value, String provenance) {

    @RecordableConstructor
    public RawFaultToleranceSetting(String name, String value, String provenance) {
        this.name = name == null ? "" : name;
        this.value = value == null ? "" : value;
        this.provenance = provenance == null ? "" : provenance;
    }
}
