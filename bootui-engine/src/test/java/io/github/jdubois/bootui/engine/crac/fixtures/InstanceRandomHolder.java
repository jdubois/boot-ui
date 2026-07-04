package io.github.jdubois.bootui.engine.crac.fixtures;

import java.security.SecureRandom;

/**
 * Keeps a {@link SecureRandom} in a singleton-bean instance field rather than a static one
 * (CRAC-RANDOM-001). Idiomatic Spring code almost always injects a PRNG this way; the checkpoint
 * freezes the field's state just as completely as it would a static field.
 */
public class InstanceRandomHolder {

    private final SecureRandom random = new SecureRandom();

    public int next() {
        return random.nextInt();
    }
}
