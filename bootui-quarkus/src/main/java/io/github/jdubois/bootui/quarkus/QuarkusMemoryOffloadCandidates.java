package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.spi.MemoryOffloadable;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

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
                if (instance instanceof MemoryOffloadable) {
                    offloadables.add(instance);
                }
            }
        }
        return offloadables;
    }
}
