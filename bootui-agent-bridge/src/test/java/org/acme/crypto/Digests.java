package org.acme.crypto;

import io.github.jdubois.bootui.agent.bridge.SecuritySinks;

/** A library's calls into the security-sinks checks. */
public final class Digests {

    private Digests() {}

    public static void digest(String algorithm) {
        SecuritySinks.digest(algorithm);
    }

    public static void verifier(Object verifier) {
        SecuritySinks.defaultVerifier(verifier);
    }
}
