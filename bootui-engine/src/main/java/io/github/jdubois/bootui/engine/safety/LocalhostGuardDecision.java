package io.github.jdubois.bootui.engine.safety;

import java.net.InetAddress;

/**
 * The typed outcome of a {@link LocalhostGuard} evaluation: either {@link Allow} (let the request
 * proceed) or {@link Reject} (return a 403 carrying the guard-owned {@link Reject#message()}).
 *
 * <p>The adapter binding is responsible for rendering the response and for any logging. {@link Allow}
 * carries whether the source was trusted via a container gateway (and which gateway), so the binding
 * can emit its once-only "trusting container gateway" warning with the matched address — without the
 * guard performing any I/O or logging itself.</p>
 */
public sealed interface LocalhostGuardDecision permits LocalhostGuardDecision.Allow, LocalhostGuardDecision.Reject {

    /** Why a request was rejected; drives the adapter's per-reason log template. */
    enum Reason {
        /** The raw TCP peer is neither loopback, in a trusted range, nor a trusted gateway. */
        NON_LOOPBACK_SOURCE,
        /** A {@code Host} header was present but not on the allow-list (DNS-rebinding defense). */
        DISALLOWED_HOST,
        /** A state-changing request was cross-site (CSRF defense). */
        CROSS_SITE_WRITE
    }

    /**
     * The request is permitted.
     *
     * @param trustedViaGateway whether trust was granted because the source matched an auto-detected
     *     container gateway (rather than loopback, a trusted range, or the allow-non-localhost bypass)
     * @param trustedGateway the matched container gateway address when {@code trustedViaGateway} is
     *     {@code true}, otherwise {@code null}
     */
    record Allow(boolean trustedViaGateway, InetAddress trustedGateway) implements LocalhostGuardDecision {}

    /**
     * The request is rejected.
     *
     * @param reason the machine-readable rejection category
     * @param message the human-readable 403 body message (identical across adapters)
     */
    record Reject(Reason reason, String message) implements LocalhostGuardDecision {}
}
