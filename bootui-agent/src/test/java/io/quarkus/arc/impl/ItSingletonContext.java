package io.quarkus.arc.impl;

import io.quarkus.arc.InjectableBean;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ArC's real {@code SingletonContext}, package-private, creating one bean on first use as a Quarkus application's
 * {@code @Singleton} or {@code @ApplicationScoped} bean is: the thread-activity sensor's IT (M5-5e) proves what such a
 * bean starts is a singleton's, never left running by the request that first used it.
 */
public final class ItSingletonContext {

    private final SingletonContext context = new SingletonContext();

    /** The bean {@code id}'s instance, created by {@code create} through ArC's shared context on first use. */
    public <T> T get(String id, Class<T> type, Supplier<T> create) {
        Bean<T> bean = new Bean<>(id, type, create);
        return context.get(bean, new CreationalContextImpl<>(bean));
    }

    private static final class Bean<T> implements InjectableBean<T> {

        private final String id;
        private final Class<T> type;
        private final Supplier<T> create;

        Bean(String id, Class<T> type, Supplier<T> create) {
            this.id = id;
            this.type = type;
            this.create = create;
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
            return Singleton.class;
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
