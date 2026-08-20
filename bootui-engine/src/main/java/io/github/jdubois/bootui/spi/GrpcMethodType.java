package io.github.jdubois.bootui.spi;

/**
 * The four gRPC method shapes, normalized away from any framework or {@code io.grpc} enum.
 *
 * <p>{@link #UNKNOWN} is deliberate: a framework may register a service whose descriptor does not expose a
 * method type, and BootUI reports that honestly instead of guessing {@link #UNARY}.</p>
 */
public enum GrpcMethodType {
    UNARY,
    CLIENT_STREAMING,
    SERVER_STREAMING,
    BIDI_STREAMING,
    UNKNOWN
}
