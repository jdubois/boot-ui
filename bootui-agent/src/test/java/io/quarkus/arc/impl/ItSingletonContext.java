package io.quarkus.arc.impl;

import io.quarkus.arc.ClientProxy;
import io.quarkus.arc.InjectableBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ArC's real {@code SingletonContext} and {@code ApplicationContext}, package-private, creating one bean on first use as
 * a Quarkus application's {@code @Singleton} and {@code @ApplicationScoped} beans are: the thread-activity sensor's IT
 * (M5-5e) proves what such a bean starts is a singleton's, never left running by the request that first used it.
 */
public final class ItSingletonContext {

    private final SingletonContext context = new SingletonContext();
    private final ApplicationContext application = new ApplicationContext();

    /** The bean {@code id}'s instance, created by {@code create} through ArC's shared context on first use. */
    public <T> T get(String id, Class<T> type, Supplier<T> create) {
        Bean<T> bean = new Bean<>(id, type, create, Singleton.class);
        return context.get(bean, new CreationalContextImpl<>(bean));
    }

    /**
     * A client proxy of the {@code @ApplicationScoped} bean {@code id}, as ArC generates one: each call resolves the
     * contextual instance through {@code ApplicationContext}, which creates it with {@code create} on the first call.
     */
    public Runnable applicationScoped(String id, Supplier<Runnable> create) {
        @SuppressWarnings("unchecked")
        Bean<Runnable> bean = new Bean<>(id, Runnable.class, create, ApplicationScoped.class);
        return new Proxy(application, bean);
    }

    /** A hand-written client proxy, delegating as ArC's generated {@code arc$delegate} does. */
    private static final class Proxy implements Runnable, ClientProxy {

        private final ApplicationContext context;
        private final Bean<Runnable> bean;

        Proxy(ApplicationContext context, Bean<Runnable> bean) {
            this.context = context;
            this.bean = bean;
        }

        @Override
        public Object arc_contextualInstance() {
            return context.get(bean, new CreationalContextImpl<>(bean));
        }

        @Override
        public InjectableBean<?> arc_bean() {
            return bean;
        }

        @Override
        public void run() {
            ((Runnable) arc_contextualInstance()).run();
        }
    }

    private static final class Bean<T> implements InjectableBean<T> {

        private final String id;
        private final Class<T> type;
        private final Supplier<T> create;
        private final Class<? extends Annotation> scope;

        Bean(String id, Class<T> type, Supplier<T> create, Class<? extends Annotation> scope) {
            this.id = id;
            this.type = type;
            this.create = create;
            this.scope = scope;
        }

        @Override
        public String getIdentifier() {
            return id;
        }

        @Override
        public Set<Type> getTypes() {
            return Set.of(type, Object.class);
        }

        @Override
        public Class<? extends Annotation> getScope() {
            return scope;
        }

        @Override
        public Class<?> getBeanClass() {
            return type;
        }

        @Override
        public T create(CreationalContext<T> creationalContext) {
            return create.get();
        }

        @Override
        public T get(CreationalContext<T> creationalContext) {
            return create(creationalContext);
        }

        @Override
        public void destroy(T instance, CreationalContext<T> creationalContext) {}
    }
}
