package io.github.jdubois.bootui.spi;

/**
 * One method of a registered gRPC service, as read from the local service descriptor.
 *
 * @param name the bare method name, or {@code null} when only {@link #fullName()} is known
 * @param fullName the fully-qualified method name reported by the framework
 * @param type the normalized method shape; {@link GrpcMethodType#UNKNOWN} when the descriptor omits it
 */
public record GrpcMethodSnapshot(String name, String fullName, GrpcMethodType type) {}
