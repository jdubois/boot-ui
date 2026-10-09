package org.apache.hc.client5.http.ssl;

/** A stand-in for Apache HttpClient 5's trust-all strategy. */
public final class TrustAllStrategy {

    public static final TrustAllStrategy INSTANCE = new TrustAllStrategy();

    private TrustAllStrategy() {}
}
