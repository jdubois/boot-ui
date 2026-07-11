package io.github.jdubois.bootui.engine.crac.fixtures;

import java.util.Random;

/** Keeps a predictable Random whose state is frozen into the checkpoint (CRAC-RANDOM-001). */
public class StaticRandomHolder {

    private static final Random RANDOM = new Random(42);

    public int next() {
        return RANDOM.nextInt();
    }
}
