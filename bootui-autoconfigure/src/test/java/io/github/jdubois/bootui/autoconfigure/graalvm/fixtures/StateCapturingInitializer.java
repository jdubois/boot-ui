package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.util.Random;

/** Triggers GRAAL-INIT-002 by capturing environment and seed state in a static initializer. */
public final class StateCapturingInitializer {

    static final String HOME = System.getenv("HOME");
    static final Random RANDOM = new Random();

    private StateCapturingInitializer() {}
}
