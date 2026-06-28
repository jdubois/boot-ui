/**
 * Framework-neutral local-only access policy: the {@link io.github.jdubois.bootui.engine.safety.LocalhostGuard}
 * decision engine plus its supporting value types and the pure {@code java.net}/{@code java.io}
 * helpers ({@link io.github.jdubois.bootui.engine.safety.CidrRange},
 * {@link io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector}).
 *
 * <p>The guard is the single source of truth for BootUI's "is this request local enough to serve?"
 * decision. Adapters translate their native request and configuration into a
 * {@link io.github.jdubois.bootui.engine.safety.LocalhostGuardRequest} /
 * {@link io.github.jdubois.bootui.engine.safety.LocalhostGuardConfig}, call
 * {@link io.github.jdubois.bootui.engine.safety.LocalhostGuard#decide}, and render the resulting
 * {@link io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision} (logging and response writing
 * stay in the adapter). Plain Java (JDK only); no framework dependency.
 */
package io.github.jdubois.bootui.engine.safety;
