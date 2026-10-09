package io.netty.handler.ssl.util;

/** A stand-in for Netty's {@code InsecureTrustManagerFactory}, whose trust manager is an anonymous nested class. */
public final class InsecureTrustManagerFactory {

    public static final Object TRUST_MANAGER = new Object() {};

    private InsecureTrustManagerFactory() {}
}
