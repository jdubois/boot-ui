package sideeffectslibrary;

import io.github.jdubois.bootui.agent.bridge.SecuritySinks;

/** A library outside the claimed packages asking for SHA-1, as the security-sinks advice reports it. */
public final class Digests {

    private Digests() {}

    public static void sha1() {
        SecuritySinks.digest("SHA-1");
    }
}
