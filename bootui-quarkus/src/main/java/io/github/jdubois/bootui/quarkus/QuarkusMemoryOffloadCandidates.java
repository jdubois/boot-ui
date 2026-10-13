package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The {@link MemoryOffloadable} beans ArC has already created, for <b>Free BootUI memory</b>.
 *
 * <p>It reads the instances held by the singleton and application contexts instead of resolving beans by type, so it
 * never creates a capture store nor links an optional integration whose capability is absent. BootUI's stores are
 * {@code @Singleton} producers; the application scope is read too so a store declared that way is not missed.</p>
 */
final class QuarkusMemoryOffloadCandidates {

    private static final List<Class<? extends Annotation>> SCOPES = List.of(Singleton.class, ApplicationScoped.class);

    private QuarkusMemoryOffloadCandidates() {}

    static List<Object> created() {
        return created(instance -> instance instanceof MemoryOffloadable);
    }

    /**
     * The created components low-footprint mode can pause: {@link LowFootprintSuspendable} ones and {@link
     * IdleReclaimable} ones the engine wraps. Read the same way, so it never creates a lazy store.
     */
    static List<Object> createdLowFootprint() {
        return created(instance -> instance instanceof LowFootprintSuspendable || instance instanceof IdleReclaimable);
    }

    private static List<Object> created(Predicate<Object> wanted) {
        ArcContainer container = Arc.container();
        if (container == null) {
            return List.of();
        }
        List<Object> offloadables = new ArrayList<>();
        for (Class<? extends Annotation> scope : SCOPES) {
            InjectableContext context = container.getActiveContext(scope);
            if (context == null) {
                continue;
            }
            for (Object instance : context.getState().getContextualInstances().values()) {
                if (wanted.test(instance)) {
                    offloadables.add(instance);
                }
            }
        }
        return offloadables;
    }
}
