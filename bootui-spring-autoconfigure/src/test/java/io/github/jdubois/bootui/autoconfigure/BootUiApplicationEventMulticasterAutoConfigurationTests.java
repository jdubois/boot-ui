package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.autoconfigure.journal.BootUiApplicationEventMulticaster;
import io.github.jdubois.bootui.autoconfigure.journal.SpringAppEventCapture;
import io.github.jdubois.bootui.engine.insights.AppEventCapture;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * BootUI's application event multicaster ({@code docs/PLAN-v2.md} §5.18, M4-8) is installed only when no one else
 * defines {@code applicationEventMulticaster}, whichever auto-configuration does, so an application such as a Spring
 * Modulith one with its event publication registry starts, and BootUI then reports that it records no application
 * event. {@link SpringModulithEventPublicationTests} pins the same with Spring Modulith's own auto-configuration.
 */
class BootUiApplicationEventMulticasterAutoConfigurationTests {

    private static final String MULTICASTER = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME;

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withPropertyValues("bootui.enabled=ON")
            .withConfiguration(AutoConfigurations.of(BootUiApplicationEventMulticasterAutoConfiguration.class));

    @Test
    void installsBootUisMulticasterWhenNoOneElseDefinesOne() {
        servlet.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MULTICASTER)).isInstanceOf(BootUiApplicationEventMulticaster.class);
            assertThat(new SpringAppEventCapture(context).get()).isEqualTo(AppEventCapture.capturing());
        });
        new ReactiveWebApplicationContextRunner()
                .withPropertyValues("bootui.enabled=ON")
                .withConfiguration(AutoConfigurations.of(BootUiApplicationEventMulticasterAutoConfiguration.class))
                .run(context ->
                        assertThat(context.getBean(MULTICASTER)).isInstanceOf(BootUiApplicationEventMulticaster.class));
    }

    @Test
    void isAbsentWheneverBootUiIsOff() {
        servlet.withPropertyValues("bootui.enabled=OFF")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiApplicationEventMulticaster.class));
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiApplicationEventMulticasterAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .as("without devtools, an enabled profile, or bootui.enabled=ON")
                        .doesNotHaveBean(BootUiApplicationEventMulticaster.class));
        new ApplicationContextRunner()
                .withPropertyValues("bootui.enabled=ON")
                .withConfiguration(AutoConfigurations.of(BootUiApplicationEventMulticasterAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .as("BootUI serves only web applications")
                        .doesNotHaveBean(BootUiApplicationEventMulticaster.class));
    }

    @Test
    void backsOffFromTheApplicationsOwnMulticaster() {
        servlet.withUserConfiguration(OwnMulticaster.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MULTICASTER)).isInstanceOf(OwnApplicationEventMulticaster.class);
            assertThat(context).doesNotHaveBean(BootUiApplicationEventMulticaster.class);
            assertThat(new SpringAppEventCapture(context).get())
                    .isEqualTo(AppEventCapture.notRecorded(AppEventCapture.CUSTOM_MULTICASTER));
        });
    }

    @Test
    void backsOffFromAnotherAutoConfigurationsMulticasterWhateverItsName() {
        // Sorted by name alone, BootUI's auto-configuration would come first and claim the bean name, so the other one,
        // defining it unconditionally as Spring Modulith's does, would fail the context's start.
        assertThat(BootUiApplicationEventMulticasterAutoConfiguration.class.getName())
                .isLessThan(ModulithLikeAutoConfiguration.class.getName());
        servlet.withConfiguration(AutoConfigurations.of(ModulithLikeAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MULTICASTER)).isInstanceOf(OwnApplicationEventMulticaster.class);
                    assertThat(context).doesNotHaveBean(BootUiApplicationEventMulticaster.class);
                    assertThat(new SpringAppEventCapture(context).get().recorded())
                            .isFalse();
                });
    }

    @Test
    void installsBootUisMulticasterInAChildContextWhoseParentHasItsOwn() {
        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext()) {
            parent.refresh();
            assertThat(parent.containsLocalBean(MULTICASTER)).isTrue();
            servlet.withParent(parent).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(MULTICASTER)).isInstanceOf(BootUiApplicationEventMulticaster.class);
                assertThat(new SpringAppEventCapture(context).get()).isEqualTo(AppEventCapture.capturing());
            });
        }
    }

    @Test
    void saysBootUisMulticasterIsNotInstalledWhenTheContextKeptItsDefault() {
        new WebApplicationContextRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context.getBean(MULTICASTER))
                            .isExactlyInstanceOf(SimpleApplicationEventMulticaster.class);
                    assertThat(new SpringAppEventCapture(context).get())
                            .as("as when the auto-configuration is excluded, which the application did not choose")
                            .isEqualTo(AppEventCapture.notRecorded(AppEventCapture.NOT_INSTALLED));
                });
    }

    @Test
    void readsBootUisMulticasterPastAProxy() {
        servlet.withUserConfiguration(ProxyingMulticaster.class).run(context -> {
            Object multicaster = context.getBean(MULTICASTER);
            assertThat(AopUtils.isAopProxy(multicaster)).isTrue();
            assertThat(multicaster).isNotInstanceOf(BootUiApplicationEventMulticaster.class);
            assertThat(new SpringAppEventCapture(context).get()).isEqualTo(AppEventCapture.capturing());
        });
    }

    @Test
    void appEventCaptureIsReadOnceTheMulticasterExists() {
        ApplicationContext context = mock(ApplicationContext.class);
        assertThat(new SpringAppEventCapture(context).get())
                .as("before the context creates its multicaster, nothing was published")
                .isEqualTo(AppEventCapture.capturing());
    }

    static class OwnApplicationEventMulticaster extends SimpleApplicationEventMulticaster {}

    @Configuration(proxyBeanMethods = false)
    static class OwnMulticaster {

        @Bean(name = MULTICASTER)
        static OwnApplicationEventMulticaster applicationEventMulticaster() {
            return new OwnApplicationEventMulticaster();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxyingMulticaster {

        @Bean
        static BeanPostProcessor bootUiTestMulticasterProxy() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!MULTICASTER.equals(beanName)) {
                        return bean;
                    }
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.setInterfaces(ApplicationEventMulticaster.class);
                    return proxy.getProxy();
                }
            };
        }
    }

    /** Defines the multicaster unconditionally, as Spring Modulith's {@code EventPublicationAutoConfiguration} does. */
    @AutoConfiguration
    static class ModulithLikeAutoConfiguration {

        @Bean(name = MULTICASTER)
        static OwnApplicationEventMulticaster applicationEventMulticaster() {
            return new OwnApplicationEventMulticaster();
        }
    }
}
