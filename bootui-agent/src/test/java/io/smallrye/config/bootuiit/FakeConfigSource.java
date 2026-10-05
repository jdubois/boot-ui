package io.smallrye.config.bootuiit;

/**
 * Stands in for SmallRye Config's system-property source in the agent's forked tests (PLAN-v2 §5.16, M5-5d): its package
 * is SmallRye Config's, so a property it reads is the configuration framework's, never recorded as a direct read.
 */
public final class FakeConfigSource {

    private FakeConfigSource() {}

    /** Reads {@code name} as a configuration source resolving its own property does. */
    public static String value(String name) {
        return System.getProperty(name);
    }
}
