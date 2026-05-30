package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeanDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeansDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.ContextBeansDescriptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link BeansController}.
 *
 * <p>Covers happy-path DTO shape for {@code /bootui/api/beans} and the
 * missing-actuator empty-DTO path. Because {@link BeanDescriptor},
 * {@link BeansDescriptor}, and {@link ContextBeansDescriptor} are all
 * {@code final} inner classes with package-private constructors, they are
 * mocked via {@link MockMakers#INLINE}.</p>
 */
class BeansControllerTests {

    private static <T> T inlineMock(Class<T> cls) {
        return mock(cls, withSettings().mockMaker(MockMakers.INLINE));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BeansEndpoint> providerOf(BeansEndpoint endpoint) {
        ObjectProvider<BeansEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(endpoint);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BeansEndpoint> emptyProvider() {
        ObjectProvider<BeansEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void beansReturnsEmptyListWhenActuatorUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new BeansController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/beans").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.beans").isArray())
                .andExpect(jsonPath("$.beans").isEmpty());
    }

    @Test
    void beansReturnsAllBeansFromAllContexts() throws Exception {
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

        ContextBeansDescriptor ctx = inlineMock(ContextBeansDescriptor.class);
        when(ctx.getBeans()).thenReturn(Map.of("alphaBean", bd1, "betaBean", bd2));

        BeansDescriptor descriptor = inlineMock(BeansDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("appCtx", ctx));

        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        when(endpoint.beans()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new BeansController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/beans").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                // results are sorted by name, so alphaBean comes before betaBean
                .andExpect(jsonPath("$.beans[0].name").value("alphaBean"))
                .andExpect(jsonPath("$.beans[0].type").value(String.class.getName()))
                .andExpect(jsonPath("$.beans[0].scope").value("singleton"))
                .andExpect(jsonPath("$.beans[0].classification").value("PLATFORM"))
                .andExpect(jsonPath("$.beans[1].name").value("betaBean"))
                .andExpect(jsonPath("$.beans[1].dependencies[0]").value("dep1"))
                .andExpect(jsonPath("$.beans[1].aliases[0]").value("listAlias"));
    }

    @Test
    void beansClassifiesTypesCorrectly() throws Exception {
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

        ContextBeansDescriptor ctx = inlineMock(ContextBeansDescriptor.class);
        when(ctx.getBeans())
                .thenReturn(Map.of(
                        "aFrameworkBean", frameworkBean,
                        "bPlatformBean", platformBean,
                        "zNullBean", nullTypeBean));

        BeansDescriptor descriptor = inlineMock(BeansDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("root", ctx));

        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        when(endpoint.beans()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new BeansController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/beans").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beans[0].name").value("aFrameworkBean"))
                .andExpect(jsonPath("$.beans[0].classification").value("FRAMEWORK"))
                .andExpect(jsonPath("$.beans[1].name").value("bPlatformBean"))
                .andExpect(jsonPath("$.beans[1].classification").value("PLATFORM"))
                .andExpect(jsonPath("$.beans[2].name").value("zNullBean"))
                .andExpect(jsonPath("$.beans[2].classification").value("OTHER"));
    }

    @Test
    void beansSortsByName() throws Exception {
        BeanDescriptor zBean = inlineMock(BeanDescriptor.class);
        doReturn(null).when(zBean).getType();
        when(zBean.getDependencies()).thenReturn(new String[0]);
        when(zBean.getAliases()).thenReturn(new String[0]);

        BeanDescriptor aBean = inlineMock(BeanDescriptor.class);
        doReturn(null).when(aBean).getType();
        when(aBean.getDependencies()).thenReturn(new String[0]);
        when(aBean.getAliases()).thenReturn(new String[0]);

        ContextBeansDescriptor ctx = inlineMock(ContextBeansDescriptor.class);
        when(ctx.getBeans()).thenReturn(Map.of("zebra", zBean, "apple", aBean));

        BeansDescriptor descriptor = inlineMock(BeansDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("root", ctx));

        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        when(endpoint.beans()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new BeansController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/beans").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beans[0].name").value("apple"))
                .andExpect(jsonPath("$.beans[1].name").value("zebra"));
    }

    @Test
    void beansSupportsServerSideFilteringAndPaging() throws Exception {
        BeanDescriptor alphaBean = inlineMock(BeanDescriptor.class);
        doReturn(String.class).when(alphaBean).getType();
        when(alphaBean.getDependencies()).thenReturn(new String[0]);
        when(alphaBean.getAliases()).thenReturn(new String[0]);

        BeanDescriptor betaBean = inlineMock(BeanDescriptor.class);
        doReturn(Runtime.class).when(betaBean).getType();
        when(betaBean.getDependencies()).thenReturn(new String[0]);
        when(betaBean.getAliases()).thenReturn(new String[0]);

        BeanDescriptor springBean = inlineMock(BeanDescriptor.class);
        doReturn(org.springframework.context.ApplicationContext.class)
                .when(springBean)
                .getType();
        when(springBean.getDependencies()).thenReturn(new String[0]);
        when(springBean.getAliases()).thenReturn(new String[0]);

        ContextBeansDescriptor ctx = inlineMock(ContextBeansDescriptor.class);
        when(ctx.getBeans())
                .thenReturn(Map.of(
                        "alphaBean", alphaBean,
                        "betaBean", betaBean,
                        "springBean", springBean));

        BeansDescriptor descriptor = inlineMock(BeansDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("root", ctx));

        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        when(endpoint.beans()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new BeansController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/beans")
                        .param("q", "java")
                        .param("classification", "PLATFORM")
                        .param("offset", "1")
                        .param("limit", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.beans").isArray())
                .andExpect(jsonPath("$.beans.length()").value(1))
                .andExpect(jsonPath("$.beans[0].name").value("betaBean"))
                .andExpect(jsonPath("$.page.total").value(3))
                .andExpect(jsonPath("$.page.matched").value(2))
                .andExpect(jsonPath("$.page.offset").value(1))
                .andExpect(jsonPath("$.page.returned").value(1))
                .andExpect(jsonPath("$.page.hasMore").value(false));
    }

    @Test
    void beansHideBootUiInternalBeansButKeepSampleAppBeans() throws Exception {
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

        ContextBeansDescriptor ctx = inlineMock(ContextBeansDescriptor.class);
        when(ctx.getBeans()).thenReturn(Map.of("bootUiAutoConfiguration", bootUiBean, "sampleApplication", sampleBean));

        BeansDescriptor descriptor = inlineMock(BeansDescriptor.class);
        when(descriptor.getContexts()).thenReturn(Map.of("root", ctx));

        BeansEndpoint endpoint = mock(BeansEndpoint.class);
        when(endpoint.beans()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new BeansController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/beans").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.beans.length()").value(1))
                .andExpect(jsonPath("$.beans[0].name").value("sampleApplication"));
    }
}
