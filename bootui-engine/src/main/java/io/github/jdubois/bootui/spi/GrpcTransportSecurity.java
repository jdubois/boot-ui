package io.github.jdubois.bootui.spi;

/**
 * Transport security state of a gRPC server or client channel, as configured locally.
 *
 * <p>{@link #UNKNOWN} means the framework does not expose the state; BootUI never probes a socket to find
 * out, so an honest "unknown" is preferred to an assumed {@link #PLAINTEXT}.</p>
 */
public enum GrpcTransportSecurity {
    PLAINTEXT,
    TLS,
    UNKNOWN
}
