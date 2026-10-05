package io.github.jdubois.bootui.autoconfigure.journal;

import io.github.jdubois.bootui.engine.insights.AppEventCapture;
import java.util.function.Supplier;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * Whether this Spring application's application events are recorded ({@code docs/PLAN-v2.md} §5.18, M4-8): BootUI
 * records them only as the context's application event multicaster, and backs off when the application, or an
 * auto-configuration such as Spring Modulith's event publication registry, defines its own. Then the checks that read
 * only application events are unavailable rather than evaluated over nothing. Shared by Spring MVC and WebFlux.
 */
public final class SpringAppEventCapture implements Supplier<AppEventCapture> {

    private static final String MULTICASTER = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME;

    private final ApplicationContext context;
    private volatile AppEventCapture known;

    public SpringAppEventCapture(ApplicationContext context) {
        this.context = context;
    }

    /**
     * Whether the context's multicaster is BootUI's, read once it exists; until then, application events are assumed
     * recorded, as nothing was published yet.
     */
    @Override
    public AppEventCapture get() {
        AppEventCapture capture = known;
        if (capture != null) {
            return capture;
        }
        capture = read();
        if (capture != null) {
            known = capture;
            return capture;
        }
        return AppEventCapture.capturing();
    }

    /** What the context's multicaster says, never creating it, or {@code null} before the context created it. */
    private AppEventCapture read() {
        try {
            if (!(context instanceof ConfigurableApplicationContext configurable)) {
                return context.containsLocalBean(MULTICASTER) ? of(context.getBean(MULTICASTER), true) : null;
            }
            ConfigurableListableBeanFactory beans = configurable.getBeanFactory();
            Object multicaster = beans.getSingleton(MULTICASTER);
            // Without a definition, the context registered its default multicaster itself: BootUI's was not installed.
            return multicaster == null ? null : of(multicaster, beans.containsBeanDefinition(MULTICASTER));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static AppEventCapture of(Object multicaster, boolean defined) {
        // Seen past an AOP proxy, which still delivers through BootUI's multicaster.
        if (BootUiApplicationEventMulticaster.class.isAssignableFrom(AopProxyUtils.ultimateTargetClass(multicaster))) {
            return AppEventCapture.capturing();
        }
        return AppEventCapture.notRecorded(
                defined ? AppEventCapture.CUSTOM_MULTICASTER : AppEventCapture.NOT_INSTALLED);
    }
}
