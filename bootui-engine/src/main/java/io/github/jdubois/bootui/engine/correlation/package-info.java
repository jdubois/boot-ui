/**
 * Exact capture-time correlation for BootUI 2.0 ({@code docs/PLAN-v2.md} §5.1).
 *
 * <p>Plain Java, framework-free: {@link io.github.jdubois.bootui.engine.correlation.BootUiCorrelation} holds the
 * {@link io.github.jdubois.bootui.spi.CorrelationContext} of the work each thread is doing, scoped so the previous
 * context is always restored, and the adapters open and restore those scopes where their framework dispatches work.
 * {@link io.github.jdubois.bootui.engine.correlation.RequestIds} and
 * {@link io.github.jdubois.bootui.engine.correlation.RunIdentity} generate the identities that the scopes carry.</p>
 */
package io.github.jdubois.bootui.engine.correlation;
