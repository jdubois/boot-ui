package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One gRPC service registered on a local server.
 *
 * @param name the fully-qualified service name (for example {@code helloworld.Greeter})
 * @param implementationClass the implementing class name, or {@code null} when the framework does not expose it
 * @param interceptors interceptor class names applied to this service, where the framework exposes them
 * @param methodCount total methods declared by the service, before any display bound is applied
 * @param methods the retained methods, ordered by name
 * @param methodsTruncated whether {@code methods} was truncated by the cardinality bound
 * @param metrics aggregate call metrics joined from existing native instrumentation
 */
public record GrpcServiceDto(
        String name,
        String implementationClass,
        List<String> interceptors,
        int methodCount,
        List<GrpcMethodDto> methods,
        boolean methodsTruncated,
        GrpcCallMetricsDto metrics) {

    public GrpcServiceDto {
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }
}
