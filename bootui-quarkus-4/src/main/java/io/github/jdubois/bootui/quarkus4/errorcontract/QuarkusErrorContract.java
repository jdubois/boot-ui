package io.github.jdubois.bootui.quarkus4.errorcontract;

import java.util.List;

/**
 * Build-time-captured holder for the host application's declared exception handlers, produced by
 * {@code ErrorContractRecorder} and exposed as a synthetic CDI bean by the deployment processor in a
 * non-production launch mode.
 *
 * <p>{@code QuarkusErrorContractProvider} injects an {@code Instance<QuarkusErrorContract>}: when the bean
 * is absent (for example in production, where the BootUI API is not wired at all) the provider reports the
 * backend unavailable and the engine serves an explicitly unavailable report; when present it maps the
 * {@link RawErrorHandler} rows onto the neutral SPI contract. The holder exists (rather than injecting a
 * raw {@code List}) so it is an unambiguous synthetic-bean type, exactly like {@code QuarkusMappings}.</p>
 *
 * @param handlers the captured declarations in Jandex index order (the engine applies classification,
 *     precedence resolution, the stable sort, free-text query and paging)
 */
public record QuarkusErrorContract(List<RawErrorHandler> handlers) {

    public QuarkusErrorContract(List<RawErrorHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }
}
