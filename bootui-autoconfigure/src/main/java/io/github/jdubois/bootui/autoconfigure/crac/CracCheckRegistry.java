package io.github.jdubois.bootui.autoconfigure.crac;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated CRaC checkpoint/restore readiness checks. Adding a check
 * means adding one focused class plus an entry here; the panel never derives checks from project
 * input.
 */
final class CracCheckRegistry {

    private static final List<CracCheck> ACTIVE_CHECKS = List.of(
            new OpenResourceFieldCheck(),
            new SocketConstructionCheck(),
            new ConnectionPoolCheck(),
            new UnmanagedThreadCheck(),
            new CapturedTimeCheck(),
            new StaticRandomFieldCheck(),
            new CapturedSecretFieldCheck(),
            new ResourceRegistrationCheck());

    private CracCheckRegistry() {}

    static List<CracCheck> activeChecks() {
        return ACTIVE_CHECKS;
    }
}
