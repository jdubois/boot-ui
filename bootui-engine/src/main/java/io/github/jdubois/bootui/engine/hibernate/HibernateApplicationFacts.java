package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

/** Application-wide facts, kept separate from persistence-unit settings. */
public record HibernateApplicationFacts(
        List<String> activeProfiles,
        Boolean deferredDatasourceInitialization,
        Boolean sqlLoggerEnabled,
        Boolean bindLoggerEnabled,
        Boolean extractLoggerEnabled,
        boolean panacheEnhancementVerified) {
    public HibernateApplicationFacts {
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
    }

    /** Compatibility entrypoint: callers that did not observe result logging leave it unknown. */
    public HibernateApplicationFacts(
            List<String> activeProfiles,
            Boolean deferredDatasourceInitialization,
            Boolean sqlLoggerEnabled,
            Boolean bindLoggerEnabled,
            boolean panacheEnhancementVerified) {
        this(
                activeProfiles,
                deferredDatasourceInitialization,
                sqlLoggerEnabled,
                bindLoggerEnabled,
                null,
                panacheEnhancementVerified);
    }

    public static HibernateApplicationFacts unknown(List<String> profiles) {
        return new HibernateApplicationFacts(profiles, null, null, null, null, false);
    }
}
