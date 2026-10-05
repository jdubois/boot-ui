package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.autoconfigure.journal.BootUiApplicationEventMulticaster;
import io.github.jdubois.bootui.autoconfigure.journal.SpringAppEventCapture;
import io.github.jdubois.bootui.engine.insights.AppEventCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * An application with Spring Modulith's event publication registry starts with BootUI, whose multicaster backs off from
 * Spring Modulith's: the regression of an application failing to start because both defined
 * {@code applicationEventMulticaster}.
 *
 * <p>Runs in its own Surefire execution, the only one with Spring Modulith on its classpath (see this module's
 * {@code pom.xml}), so its types are named rather than imported.</p>
 */
@Tag("spring-modulith")
class SpringModulithEventPublicationTests {

    private static final String MULTICASTER = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME;
    private static final String REPOSITORY = "org.springframework.modulith.events.core.EventPublicationRepository";
    private static final String PERSISTENT_MULTICASTER =
            "org.springframework.modulith.events.support.PersistentApplicationEventMulticaster";

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withPropertyValues("bootui.enabled=ON")
            .withConfiguration(AutoConfigurations.of(
                    BootUiApplicationEventMulticasterAutoConfiguration.class,
                    type(BootUiApplicationEventMulticasterAutoConfiguration.MODULITH_EVENT_PUBLICATION)));

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bootUisMulticasterBacksOffFromSpringModulithsEventPublicationRegistry() {
        Class repository = type(REPOSITORY);
        servlet.withBean(repository, () -> mock(repository)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MULTICASTER)).isInstanceOf(type(PERSISTENT_MULTICASTER));
            assertThat(context).doesNotHaveBean(BootUiApplicationEventMulticaster.class);
            AppEventCapture capture = new SpringAppEventCapture(context).get();
            assertThat(capture).isEqualTo(AppEventCapture.notRecorded(AppEventCapture.CUSTOM_MULTICASTER));
            assertThat(capture.reason()).contains("Spring Modulith", "does not record application events");
        });
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            throw new AssertionError(name + " must be on this Surefire execution's classpath", ex);
        }
    }
}
