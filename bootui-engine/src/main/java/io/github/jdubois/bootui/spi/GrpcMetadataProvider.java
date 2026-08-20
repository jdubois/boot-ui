package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral seam behind the gRPC panel's registry view: it reports the host application's locally
 * configured gRPC servers, their registered services and methods, and the framework-managed outbound client
 * channels, already mapped to neutral snapshot records.
 *
 * <p>The Spring Boot adapter implements this over {@code io.grpc.BindableService} beans plus the
 * {@code spring.grpc.server.*} / {@code spring.grpc.client.channels.*} configuration Spring gRPC binds. The
 * Quarkus adapter implements it over {@code @GrpcService} CDI beans plus {@code quarkus.grpc.server.*} /
 * {@code quarkus.grpc.clients.*}. Mapping stays adapter-side on purpose: the {@code io.grpc} descriptor types
 * and each framework's configuration model must never be linked from an always-loaded engine class (R2).</p>
 *
 * <p>Implementations must be strictly read-only. Building a snapshot must not create a channel or stub,
 * resolve a name, issue an RPC, enable server reflection, or register an interceptor.</p>
 */
public interface GrpcMetadataProvider {

    /**
     * Whether a supported gRPC integration is present and usable right now. {@code false} means the engine
     * serves a stable empty report carrying {@link #unavailableReason()}.
     */
    boolean available();

    /**
     * Why gRPC metadata is unavailable, phrased for the host framework, or {@code null} when
     * {@link #available()} is {@code true}.
     */
    String unavailableReason();

    /**
     * The detected integration name shown in the UI (for example {@code Spring gRPC} or {@code Quarkus gRPC}),
     * or {@code null} when unavailable.
     */
    String integration();

    /**
     * One consistent read of the local servers, client channels, and any non-fatal notices. Called once per
     * report so a provider never has to keep mutable state between calls.
     */
    GrpcRegistrySnapshot registry();
}
