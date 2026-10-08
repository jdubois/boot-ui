package org.apache.hc.core5.ssl;

/** A stand-in for Apache HttpCore 5's builder, whose trust manager delegate keeps its trust strategy. */
public final class SSLContextBuilder {

    private SSLContextBuilder() {}

    public static final class TrustManagerDelegate {

        private final Object trustManager;
        private final Object trustStrategy;

        public TrustManagerDelegate(Object trustManager, Object trustStrategy) {
            this.trustManager = trustManager;
            this.trustStrategy = trustStrategy;
        }
    }
}
