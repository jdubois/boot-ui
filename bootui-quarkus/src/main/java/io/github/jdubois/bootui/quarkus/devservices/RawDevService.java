package io.github.jdubois.bootui.quarkus.devservices;

import io.quarkus.runtime.annotations.RecordableConstructor;
import java.util.Map;

/**
 * The raw, build-time-captured metadata for a single Quarkus Dev Service, recorded from a
 * {@code DevServicesResultBuildItem} by the deployment processor and replayed into the runtime via a
 * {@code @Recorder} (see {@code DevServicesRecorder}).
 *
 * <p>Quarkus exposes Dev Services only at <em>build time</em>: there is no runtime API to list the containers
 * an extension started. So the deployment processor records each service's name, human-readable description,
 * container id and the config map it injected (e.g. {@code quarkus.datasource.jdbc.url}); the runtime
 * {@code QuarkusDevServicesProvider} masks the config values and maps them to the neutral {@code DevServiceDto}.
 * Because the config map often carries credentials (JDBC URLs, passwords), values are masked at the runtime
 * provider via the shared {@code SecretMasker}, never trusted as captured.</p>
 *
 * <p>Serialized into the Quarkus bytecode recorder, so the canonical constructor is {@link RecordableConstructor};
 * the module compiles with {@code -parameters} so parameter names match the record components.</p>
 *
 * @param name the dev-service name (e.g. {@code "default"} datasource)
 * @param description human-readable description (e.g. container image / endpoint), may be empty
 * @param containerId the container id, may be empty when not container-backed
 * @param config the config entries the dev service injected (masked at runtime), never null
 */
public record RawDevService(String name, String description, String containerId, Map<String, String> config) {

    @RecordableConstructor
    public RawDevService(String name, String description, String containerId, Map<String, String> config) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.containerId = containerId == null ? "" : containerId;
        this.config = config == null ? Map.of() : Map.copyOf(config);
    }
}
