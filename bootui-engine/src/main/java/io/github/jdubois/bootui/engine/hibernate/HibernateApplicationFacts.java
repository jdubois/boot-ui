package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

/** Application-wide facts, kept separate from persistence-unit settings. */
public record HibernateApplicationFacts(
        List<String> activeProfiles,
        Boolean deferredDatasourceInitialization,
        Boolean sqlLoggerEnabled,
        Boolean bindLoggerEnabled,
        boolean panacheEnhancementVerified) {
    public HibernateApplicationFacts {
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
    }

    public static HibernateApplicationFacts unknown(List<String> profiles) {
        return new HibernateApplicationFacts(profiles, null, null, null, false);
    }
}
