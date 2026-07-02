package io.github.jdubois.bootui.quarkus.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.container.ResourceInfo;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuarkusResourceHandlers#describe(ResourceInfo)} — the pure formatting half of the
 * handler resolution (the other half, {@link QuarkusResourceHandlers#currentHandler()}, reaches into
 * RESTEasy Reactive's live current-request state and is proven end to end by the {@code @QuarkusTest}
 * regression test {@code BootUiQuarkusLiveActivityExceptionCorrelationTest}, the same split PR #492 used for
 * the sibling method/path resolution).
 */
class QuarkusResourceHandlersTest {

    @Test
    void describesResourceClassAndMethod() throws NoSuchMethodException {
        Method method = Probe.class.getDeclaredMethod("boom");
        ResourceInfo info = fakeResourceInfo(Probe.class, method);

        assertThat(QuarkusResourceHandlers.describe(info)).isEqualTo("Probe#boom");
    }

    @Test
    void returnsNullForNullResourceInfo() {
        assertThat(QuarkusResourceHandlers.describe(null)).isNull();
    }

    @Test
    void returnsNullWhenResourceClassIsMissing() throws NoSuchMethodException {
        Method method = Probe.class.getDeclaredMethod("boom");
        ResourceInfo info = fakeResourceInfo(null, method);

        assertThat(QuarkusResourceHandlers.describe(info)).isNull();
    }

    @Test
    void returnsNullWhenResourceMethodIsMissing() {
        ResourceInfo info = fakeResourceInfo(Probe.class, null);

        assertThat(QuarkusResourceHandlers.describe(info)).isNull();
    }

    private static ResourceInfo fakeResourceInfo(Class<?> resourceClass, Method resourceMethod) {
        return new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return resourceMethod;
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }
        };
    }

    /** Stand-in JAX-RS resource, used only to obtain a real {@link Method} to format. */
    private static final class Probe {
        @SuppressWarnings("unused")
        void boom() {}
    }
}
