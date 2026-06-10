package io.github.jdubois.bootui.autoconfigure.crac.fixtures;

import java.security.SecureRandom;

/** Keeps a static SecureRandom whose state is frozen into the checkpoint (CRAC-RANDOM-001). */
public class StaticRandomHolder {

    private static final SecureRandom RANDOM = new SecureRandom();

    public int next() {
        return RANDOM.nextInt();
    }
}
