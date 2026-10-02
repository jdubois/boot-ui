package io.github.jdubois.bootui.autoconfigure.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.insights.ProxyBoundaries.Boundary;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class SpringProxyBoundariesTests {

    private final SpringProxyBoundaries boundaries = new SpringProxyBoundaries(null);

    @Test
    void annotationsDeclaredOnAnInterfaceOrTheClassCountAndOnlyRequiringPropagationsAreTransactional() {
        assertThat(boundaries.resolve(frame(Orders.class, "place"))).isEqualTo(new Boundary(true, Set.of(), false));
        assertThat(boundaries.resolve(frame(Orders.class, "browse")))
                .as("SUPPORTS runs without a transaction when none is open")
                .isEqualTo(Boundary.NONE);
        assertThat(boundaries.resolve(frame(Notifier.class, "shipped"))).isEqualTo(new Boundary(false, Set.of(), true));
        assertThat(boundaries.resolve(frame(Prices.class, "price")))
                .as("a @Cacheable without names takes the class's @CacheConfig")
                .isEqualTo(new Boundary(false, Set.of("prices"), false));
    }

    @Test
    void unknownClassesAndOverloadsThatDisagreeAreNotResolvedWhileUnannotatedMethodsHaveNoBoundary() {
        assertThat(boundaries.resolve("com.example.Missing.run(Missing.java:1)"))
                .isNull();
        assertThat(boundaries.resolve(frame(Overloaded.class, "save"))).isNull();
        assertThat(boundaries.resolve(frame(Overloaded.class, "plain"))).isEqualTo(Boundary.NONE);
        assertThat(boundaries.resolve(frame(Orders.class, "lambda$place$0"))).isEqualTo(Boundary.NONE);
        assertThat(boundaries.notApplicable()).isNull();
    }

    private static String frame(Class<?> type, String method) {
        return type.getName() + "." + method + "(" + type.getSimpleName() + ".java:1)";
    }

    interface OrderOperations {

        @Transactional
        void place();
    }

    static class Orders implements OrderOperations {

        @Override
        public void place() {}

        @Transactional(propagation = Propagation.SUPPORTS)
        public void browse() {}
    }

    @Async
    static class Notifier {

        public void shipped() {}
    }

    @CacheConfig(cacheNames = "prices")
    static class Prices {

        @Cacheable
        public long price() {
            return 1;
        }
    }

    static class Overloaded {

        @Transactional
        public void save(String value) {}

        public void save(int value) {}

        public void plain() {}
    }
}
