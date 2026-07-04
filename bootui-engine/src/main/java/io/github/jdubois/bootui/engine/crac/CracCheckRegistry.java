package io.github.jdubois.bootui.engine.crac;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated CRaC checkpoint/restore readiness checks. Adding a check
 * means adding one focused class plus an entry here; the panel never derives checks from project
 * input.
 */
final class CracCheckRegistry {

    private static final List<CracCheck> ACTIVE_CHECKS = List.of(
            new OpenResourceFieldCheck(),
            new FileHandleCheck(),
            new SocketConstructionCheck(),
            new ConnectionPoolCheck(),
            new UnmanagedHttpClientFieldCheck(),
            new CacheManagerCheck(),
            new UnmanagedThreadCheck(),
            new ScheduledFixedRateTaskCheck(),
            new CapturedTimeCheck(),
            new CapturedConfigurationCheck(),
            new RandomFieldCheck(),
            new CapturedSecretFieldCheck(),
            new ResourceRegistrationCheck(),
            new CracDependencyCheck());

    private CracCheckRegistry() {}

    static List<CracCheck> activeChecks() {
        return ACTIVE_CHECKS;
    }
}
