package io.github.jdubois.bootui.autoconfigure.hibernate;

import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import java.util.function.Function;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/** Adds Spring runtime applicability signals to the engine's neutral property lookup. */
public final class SpringHibernatePropertyLookup implements Function<String, String> {

    private static final String SERVLET_WEB_APPLICATION_CONTEXT =
            "org.springframework.web.context.WebApplicationContext";

    private final Environment environment;
    private final boolean servletWebApplication;

    public SpringHibernatePropertyLookup(Environment environment, boolean servletWebApplication) {
        this.environment = environment;
        this.servletWebApplication = servletWebApplication;
    }

    public static boolean isServletWebApplication(ApplicationContext applicationContext) {
        return implementsType(applicationContext.getClass(), SERVLET_WEB_APPLICATION_CONTEXT);
    }

    @Override
    public String apply(String key) {
        if (HibernateScanner.OPEN_IN_VIEW_APPLICABLE_PROPERTY.equals(key)) {
            return Boolean.toString(servletWebApplication);
        }
        return environment.getProperty(key);
    }

    private static boolean implementsType(Class<?> type, String typeName) {
        Class<?> current = type;
        while (current != null) {
            for (Class<?> candidate : current.getInterfaces()) {
                if (typeName.equals(candidate.getName()) || implementsType(candidate, typeName)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
