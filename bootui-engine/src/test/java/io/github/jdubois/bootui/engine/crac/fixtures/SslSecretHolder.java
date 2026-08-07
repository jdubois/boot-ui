package io.github.jdubois.bootui.engine.crac.fixtures;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/** Holds TLS context and manager state in fields (CRAC-SECRET-002). */
public class SslSecretHolder {

    private final SSLContext sslContext;
    private final TrustManager[] trustManagers;

    public SslSecretHolder(SSLContext sslContext) {
        this.sslContext = sslContext;
        this.trustManagers = new TrustManager[0];
    }

    public SSLContext sslContext() {
        return sslContext;
    }

    public TrustManager[] trustManagers() {
        return trustManagers;
    }
}
