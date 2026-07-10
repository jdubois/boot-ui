package io.github.jdubois.bootui.engine.crac.fixtures;

import java.security.SecureRandom;

/** Keeps an unseeded SecureRandom for the informational CRAC-RANDOM-002 check. */
public class NoArgSecureRandomHolder {

    private final SecureRandom random = new SecureRandom();

    public int next() {
        return random.nextInt();
    }
}
