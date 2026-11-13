package io.github.jdubois.bootui.autoconfigure.compatibility;

import io.github.jdubois.bootui.engine.compatibility.PlatformCompatibility;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aot.AotDetector;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.NativeDetector;
import org.springframework.core.Ordered;
import org.springframework.core.SpringVersion;

/**
 * Runs inside Spring Boot's failure-reporting boundary, before environment post-processors are loaded.
 * Only stable Spring APIs and the JDK-only platform policy are linked on this early path.
 */
public final class BootUiCompatibilityListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Log LOG = LogFactory.getLog(BootUiCompatibilityListener.class);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 9;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (NativeDetector.inNativeImage() || AotDetector.useGeneratedArtifacts()) {
            return;
        }
        boolean boot4Api;
        try {
            Class.forName(
                    "org.springframework.boot.EnvironmentPostProcessor",
                    false,
                    event.getSpringApplication().getClassLoader());
            boot4Api = true;
        } catch (ClassNotFoundException | LinkageError ex) {
            boot4Api = false;
        }
        for (var finding :
                PlatformCompatibility.spring(SpringBootVersion.getVersion(), SpringVersion.getVersion(), boot4Api)) {
            switch (finding.severity()) {
                case ERROR ->
                    throw new BootUiCompatibilityException(finding.id() + ": " + finding.message(), finding.action());
                case WARNING -> LOG.warn(finding.diagnostic());
                case INFO -> LOG.info(finding.diagnostic());
            }
        }
    }
}
