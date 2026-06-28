package io.github.jdubois.bootui.engine.safety;

/**
 * Framework-neutral tri-state controlling whether an auto-detected container gateway address is
 * trusted as loopback-equivalent by the {@link LocalhostGuard}.
 *
 * <p>Adapters map their own configuration onto this enum (the Spring adapter from
 * {@code bootui.trust-container-gateway}'s {@code OFF/AUTO/ON} mode, the Quarkus adapter by parsing
 * the same property from MP Config). Unknown or absent values must map to {@link #OFF} so the guard
 * fails closed.</p>
 *
 * <ul>
 *   <li>{@link #OFF} (default) — never trust a gateway.</li>
 *   <li>{@link #AUTO} — trust a detected gateway only when container heuristics indicate the JVM is
 *       running inside a container.</li>
 *   <li>{@link #ON} — trust a detected gateway whenever at least one was detected, even if the
 *       container heuristics were inconclusive.</li>
 * </ul>
 */
public enum GatewayTrust {
    OFF,
    AUTO,
    ON
}
