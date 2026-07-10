package io.github.jdubois.bootui.engine.crac.fixtures;

import java.security.SecureRandom;

/**
 * Explicitly seeds a {@link SecureRandom} in a singleton-bean field (CRAC-RANDOM-001).
 */
public class InstanceRandomHolder {

    private final SecureRandom random = new SecureRandom(new byte[] {1, 2, 3, 4});

    public int next() {
        return random.nextInt();
    }
}
