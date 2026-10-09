package io.netty.handler.ssl;

/** A stand-in for Netty's error-message wrapper, which keeps what it wraps in {@code wrapped}. */
public final class EnhancingX509ExtendedTrustManager {

    private final Object wrapped;

    public EnhancingX509ExtendedTrustManager(Object wrapped) {
        this.wrapped = wrapped;
    }
}
