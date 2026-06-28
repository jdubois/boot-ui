package io.github.jdubois.bootui.autoconfigure.beans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.BeanSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeanDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeansDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.ContextBeansDescriptor;

/**
 * Tests for {@link SpringBeanProvider}, the single Actuator touch-point that maps the raw beans
 * descriptor, applies BootUI's self-data filter and computes the Spring-flavored classification.
 *
 * <p>This is the logic moved out of the old {@code BeansController} (the controller test now mocks the
 * engine service), so it is covered directly here. Because {@link BeanDescriptor},
 * {@link BeansDescriptor} and {@link ContextBeansDescriptor} are all {@code final} inner classes with
 * package-private constructors, they are mocked via {@link MockMakers#INLINE}.</p>
 */
class SpringBeanProviderTests {

    private final BootUiSelfDataFilter selfDataFilter = BootUiSelfDataFilter.defaults();

    private static <T> T inlineMock(Class<T> cls) {
        return mock(cls, withSettings().mockMaker(MockMakers.INLINE));
    }

    @Test
    void availableReflectsEndpointPresenceAndBeansAreEmptyWhenAbsent() {
        SpringBeanProvider absent = new SpringBeanProvider(() -> null, selfDataFilter);
        assertThat(absent.available()).isFalse();
        assertThat(absent.beans()).isEmpty();

        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        SpringBeanProvider present = new SpringBeanProvider(() -> endpoint, selfDataFilter);
        assertThat(present.available()).isTrue();
    }

    @Test
    void mapsBeansFromAllContexts() {
        BeanDescriptor bd1 = inlineMock(BeanDescriptor.class);
        doReturn(String.class).when(bd1).getType();
        when(bd1.getScope()).thenReturn("singleton");
        when(bd1.getResource()).thenReturn("com/example/Config.class");
        when(bd1.getDependencies()).thenReturn(new String[0]);
        when(bd1.getAliases()).thenReturn(new String[0]);

        BeanDescriptor bd2 = inlineMock(BeanDescriptor.class);
        doReturn(java.util.ArrayList.class).when(bd2).getType();
        when(bd2.getScope()).thenReturn("prototype");
        when(bd2.getDependencies()).thenReturn(new String[] {"dep1"});
        when(bd2.getAliases()).thenReturn(new String[] {"listAlias"});

        SpringBeanProvider provider = providerOf(Map.of("alphaBean", bd1, "betaBean", bd2));

        List<BeanSummary> beans = provider.beans();
        assertThat(beans).hasSize(2);
        BeanSummary alpha = beans.stream()
                .filter(b -> b.name().equals("alphaBean"))
                .findFirst()
                .orElseThrow();
        assertThat(alpha.type()).isEqualTo(String.class.getName());
        assertThat(alpha.scope()).isEqualTo("singleton");
        assertThat(alpha.classification()).isEqualTo("PLATFORM");
        BeanSummary beta = beans.stream()
                .filter(b -> b.name().equals("betaBean"))
                .findFirst()
                .orElseThrow();
        assertThat(beta.dependencies()).containsExactly("dep1");
        assertThat(beta.aliases()).containsExactly("listAlias");
    }

    @Test
    void classifiesTypesCorrectly() {
        BeanDescriptor frameworkBean = inlineMock(BeanDescriptor.class);
        doReturn(org.springframework.context.ApplicationContext.class)
                .when(frameworkBean)
                .getType();
        when(frameworkBean.getDependencies()).thenReturn(new String[0]);
        when(frameworkBean.getAliases()).thenReturn(new String[0]);

        BeanDescriptor nullTypeBean = inlineMock(BeanDescriptor.class);
        doReturn(null).when(nullTypeBean).getType();
        when(nullTypeBean.getDependencies()).thenReturn(new String[0]);
        when(nullTypeBean.getAliases()).thenReturn(new String[0]);

        BeanDescriptor platformBean = inlineMock(BeanDescriptor.class);
        doReturn(java.lang.Runtime.class).when(platformBean).getType();
        when(platformBean.getDependencies()).thenReturn(new String[0]);
        when(platformBean.getAliases()).thenReturn(new String[0]);

        SpringBeanProvider provider = providerOf(Map.of(
                "aFrameworkBean", frameworkBean,
                "bPlatformBean", platformBean,
                "zNullBean", nullTypeBean));

        Map<String, String> byName = provider.beans().stream()
                .collect(java.util.stream.Collectors.toMap(BeanSummary::name, BeanSummary::classification));
        assertThat(byName)
                .containsEntry("aFrameworkBean", "FRAMEWORK")
                .containsEntry("bPlatformBean", "PLATFORM")
                .containsEntry("zNullBean", "OTHER");
    }

    @Test
    void hidesBootUiInternalBeansButKeepsSampleAppBeans() {
        BeanDescriptor bootUiBean = inlineMock(BeanDescriptor.class);
        doReturn(BootUiAutoConfiguration.class).when(bootUiBean).getType();
        when(bootUiBean.getResource())
                .thenReturn("io/github/jdubois/bootui/autoconfigure/BootUiAutoConfiguration.class");
        when(bootUiBean.getDependencies()).thenReturn(new String[0]);
        when(bootUiBean.getAliases()).thenReturn(new String[0]);

        BeanDescriptor sampleBean = inlineMock(BeanDescriptor.class);
        doReturn(null).when(sampleBean).getType();
        when(sampleBean.getResource()).thenReturn("io/github/jdubois/bootui/sample/SampleApplication.class");
        when(sampleBean.getDependencies()).thenReturn(new String[0]);
        when(sampleBean.getAliases()).thenReturn(new String[0]);

        SpringBeanProvider provider =
                providerOf(Map.of("bootUiAutoConfiguration", bootUiBean, "sampleApplication", sampleBean));

        List<BeanSummary> beans = provider.beans();
        assertThat(beans).hasSize(1);
        assertThat(beans.get(0).name()).isEqualTo("sampleApplication");
    }

    private SpringBeanProvider providerOf(Map<String, BeanDescriptor> beans) {
        ContextBeansDescriptor ctx = inlineMock(ContextBeansDescriptor.class);
        when(ctx.getBeans()).thenReturn(beans);
        BeansDescriptor descriptor = inlineMock(BeansDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("root", ctx));
        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        when(endpoint.beans()).thenReturn(descriptor);
        return new SpringBeanProvider(() -> endpoint, selfDataFilter);
    }
}
