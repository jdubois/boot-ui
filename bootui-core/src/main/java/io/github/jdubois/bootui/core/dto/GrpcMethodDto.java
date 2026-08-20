package io.github.jdubois.bootui.core.dto;

/**
 * One method exposed by a registered gRPC service.
 *
 * @param name the bare method name (for example {@code SayHello})
 * @param fullName the fully-qualified method name (for example {@code helloworld.Greeter/SayHello})
 * @param type {@code UNARY}, {@code CLIENT_STREAMING}, {@code SERVER_STREAMING}, {@code BIDI_STREAMING} or
 *     {@code UNKNOWN}
 * @param metrics aggregate call metrics joined from existing native instrumentation
 */
public record GrpcMethodDto(String name, String fullName, String type, GrpcCallMetricsDto metrics) {}
