package io.github.jdubois.bootui.autoconfigure.graalvm;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated native-image readiness checks. Adding a check means
 * adding one focused class plus an entry here; the panel never derives checks from project input.
 */
final class GraalVmCheckRegistry {

    private static final List<GraalVmCheck> ACTIVE_CHECKS = List.of(
            new ReflectionUsageCheck(),
            new DynamicProxyCheck(),
            new ResourceAccessCheck(),
            new SerializationCheck(),
            new NativeAccessCheck());

    private GraalVmCheckRegistry() {}

    static List<GraalVmCheck> activeChecks() {
        return ACTIVE_CHECKS;
    }
}
