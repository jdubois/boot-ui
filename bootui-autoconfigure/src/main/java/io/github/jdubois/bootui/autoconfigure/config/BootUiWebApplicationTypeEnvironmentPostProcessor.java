package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.util.ClassUtils;

/**
 * Lets BootUI serve its console from a non-web (command-line) Spring Boot
 * application by forcing the host into a servlet web application when BootUI is
 * active.
 *
 * <p>BootUI's panels are served by Spring MVC and gated on
 * {@code @ConditionalOnWebApplication(SERVLET)}. A genuine command-line app
 * (one that sets {@code spring.main.web-application-type=none} or
 * {@code SpringApplication#setWebApplicationType(NONE)}) never starts a servlet
 * container, so BootUI would stay silent there even though the starter ships an
 * embedded container. When BootUI is active this post-processor contributes
 * {@code spring.main.web-application-type=servlet} as the highest-priority
 * property so the host boots a servlet container and the console becomes
 * reachable.</p>
 *
 * <p>This only ever runs when BootUI itself is active (per
 * {@link BootUiActivationCondition}), which by default is limited to development
 * contexts (dev/local profiles, devtools, or {@code bootui.enabled=ON}). It does
 * nothing for applications that are already servlet web applications, that are
 * explicitly configured as reactive, or that have no embedded servlet container
 * on the classpath. The behaviour can be disabled with
 * {@code bootui.force-web=false}.</p>
 */
public class BootUiWebApplicationTypeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Runs after {@code ConfigDataEnvironmentPostProcessor} so active profiles are resolved. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    static final String FORCE_WEB_PROPERTY = "bootui.force-web";

    static final String WEB_APPLICATION_TYPE_PROPERTY = "spring.main.web-application-type";

    static final String PROPERTY_SOURCE_NAME = "bootui-web-application-type";

    private static final String DISPATCHER_SERVLET_CLASS = "org.springframework.web.servlet.DispatcherServlet";

    private static final String[] SERVLET_CONTAINER_CLASSES = {
        "org.apache.catalina.startup.Tomcat", "org.eclipse.jetty.server.Server", "io.undertow.Undertow"
    };

    private final Log log;

    public BootUiWebApplicationTypeEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(FORCE_WEB_PROPERTY, Boolean.class, true)) {
            return;
        }
        ClassLoader classLoader = application.getClassLoader();
        if (!BootUiActivationCondition.resolve(environment, classLoader).enabled()) {
            return;
        }

        String configuredType = environment.getProperty(WEB_APPLICATION_TYPE_PROPERTY);
        if (isReactive(application.getWebApplicationType(), configuredType)) {
            // BootUI is servlet-only; never override an explicitly reactive application.
            return;
        }
        if (!isEffectivelyNonServlet(application.getWebApplicationType(), configuredType)) {
            // Already a servlet web application: nothing to force.
            return;
        }
        if (!servletStackPresent(classLoader)) {
            // No DispatcherServlet or embedded container to start: leave the app untouched.
            return;
        }

        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of(WEB_APPLICATION_TYPE_PROPERTY, WebApplicationType.SERVLET.name())));
        log.info("BootUI is active in a non-web application; forcing servlet web mode (" + WEB_APPLICATION_TYPE_PROPERTY
                + "=servlet) so the BootUI console can be served. Set " + FORCE_WEB_PROPERTY
                + "=false to disable this.");
    }

    private boolean isReactive(WebApplicationType current, String configuredType) {
        return current == WebApplicationType.REACTIVE || "reactive".equalsIgnoreCase(normalize(configuredType));
    }

    private boolean isEffectivelyNonServlet(WebApplicationType current, String configuredType) {
        return current == WebApplicationType.NONE || "none".equalsIgnoreCase(normalize(configuredType));
    }

    private boolean servletStackPresent(ClassLoader classLoader) {
        if (!ClassUtils.isPresent(DISPATCHER_SERVLET_CLASS, classLoader)) {
            return false;
        }
        for (String containerClass : SERVLET_CONTAINER_CLASSES) {
            if (ClassUtils.isPresent(containerClass, classLoader)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
