package io.github.jdubois.bootui.quarkus4.errorcontract;

import io.github.jdubois.bootui.spi.ErrorContractProvider;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Quarkus {@link ErrorContractProvider} backed by the build-time-captured {@link QuarkusErrorContract}
 * holder.
 *
 * <p>The deployment processor exposes the synthetic {@code QuarkusErrorContract} bean in non-production
 * launch modes. This provider is therefore wired unconditionally but tolerates the bean's absence: an
 * unsatisfied {@code Instance} means {@link #available()} is {@code false} and the engine renders an
 * explicitly unavailable report.</p>
 *
 * <p>All discovery, media-type joining and BootUI self-filtering happen at <em>build time</em>, where the
 * Jandex index carries the annotations and generic signatures Quarkus does not expose at runtime. This
 * provider only maps the already-prepared rows one-to-one onto the neutral SPI contract; the engine's
 * {@code ErrorContractService} then classifies, ranks, sorts, queries and pages them. The mapped list is
 * memoized so the engine's catalogue cache stays O(1).</p>
 */
@Singleton
public class QuarkusErrorContractProvider implements ErrorContractProvider {

    static final String UNAVAILABLE_REASON =
            "Not available: the build-time error-contract capture is not wired in this launch mode, so"
                    + " declared exception mappers cannot be catalogued.";

    private final Instance<QuarkusErrorContract> capturedHandlers;

    private volatile List<ErrorHandlerDescriptor> cached;

    @Inject
    public QuarkusErrorContractProvider(Instance<QuarkusErrorContract> capturedHandlers) {
        this.capturedHandlers = capturedHandlers;
    }

    @Override
    public boolean available() {
        return !capturedHandlers.isUnsatisfied();
    }

    @Override
    public String unavailableReason() {
        return available() ? null : UNAVAILABLE_REASON;
    }

    @Override
    public List<ErrorHandlerDescriptor> handlers() {
        if (capturedHandlers.isUnsatisfied()) {
            return List.of();
        }
        List<ErrorHandlerDescriptor> snapshot = cached;
        if (snapshot == null) {
            snapshot = toDescriptors(capturedHandlers.get().handlers());
            cached = snapshot;
        }
        return snapshot;
    }

    /**
     * Maps the build-time-prepared rows one-to-one onto the neutral contract. Package-private and static so
     * the mapping is unit-testable without the CDI {@code Instance} plumbing.
     */
    static List<ErrorHandlerDescriptor> toDescriptors(List<RawErrorHandler> rows) {
        return rows.stream()
                .map(raw -> new ErrorHandlerDescriptor(
                        raw.source(),
                        raw.componentClassName(),
                        raw.methodName(),
                        raw.exceptionTypeNames(),
                        raw.scope(),
                        raw.scopeTarget(),
                        raw.declaredOrder(),
                        false, // Jakarta REST providers declare their priority statically
                        raw.declaredStatus(),
                        raw.dynamicStatus(),
                        raw.returnTypeName(),
                        raw.bodyTypeName(),
                        raw.produces()))
                .toList();
    }
}
