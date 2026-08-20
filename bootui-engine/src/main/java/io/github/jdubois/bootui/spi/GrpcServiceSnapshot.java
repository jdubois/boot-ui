package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * One gRPC service registered on a local server, already mapped away from {@code io.grpc} types.
 *
 * @param name the fully-qualified service name
 * @param implementationClass the implementing class name, or {@code null} when the framework does not expose it
 * @param interceptors interceptor class names applied to this service, where the framework exposes them
 * @param methods the service's methods; the engine applies ordering and cardinality bounds
 */
public record GrpcServiceSnapshot(
        String name, String implementationClass, List<String> interceptors, List<GrpcMethodSnapshot> methods) {

    public GrpcServiceSnapshot {
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }
}
