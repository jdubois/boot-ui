package io.netty.handler.ssl.util;

/** A stand-in for Netty 4.1's wrapper, which keeps what it wraps in {@code delegate}. */
public final class X509TrustManagerWrapper {

    private final Object delegate;

    public X509TrustManagerWrapper(Object delegate) {
        this.delegate = delegate;
    }
}
