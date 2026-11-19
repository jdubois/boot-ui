package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/** Reports persisted configuration only after ConfigData and logging have been initialized. */
public final class BootUiOverridesLoggingListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Log LOG = LogFactory.getLog(BootUiOverridesLoggingListener.class);

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        var environment = event.getEnvironment();
        if (!(environment.getPropertySources().get(BootUiOverridesPropertySource.NAME)
                        instanceof BootUiOverridesPropertySource source)
                || source.file() == null) {
            return;
        }
        if (source.unreadable()) {
            LOG.warn(
                    "Unable to read BootUI overrides file " + source.file() + "; no persisted overrides were applied.");
        }
        if (source.protectedKeys() > 0) {
            LOG.warn("Ignored " + source.protectedKeys() + " protected BootUI safety setting(s) in " + source.file()
                    + "; configure safety settings in host-owned configuration instead.");
        }
        if (source.getSource().isEmpty()) {
            return;
        }
        String message = "Loaded " + source.getSource().size() + " persisted BootUI override(s) from " + source.file();
        if (BootUiActivationCondition.resolve(
                        environment, event.getSpringApplication().getClassLoader())
                .enabled()) {
            LOG.info(message);
        } else {
            LOG.warn(message + " while BootUI is inactive; these application settings still apply.");
        }
    }
}
