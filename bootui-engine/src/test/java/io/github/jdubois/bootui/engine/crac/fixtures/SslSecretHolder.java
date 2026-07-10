package io.github.jdubois.bootui.engine.crac.fixtures;

import javax.net.ssl.SSLContext;

/** Holds initialized TLS material in a field (CRAC-SECRET-001). */
public class SslSecretHolder {

    private final SSLContext sslContext;

    public SslSecretHolder(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    public SSLContext sslContext() {
        return sslContext;
    }
}
