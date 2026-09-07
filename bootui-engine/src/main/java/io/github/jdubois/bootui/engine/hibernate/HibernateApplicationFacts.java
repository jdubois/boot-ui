package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

/** Application-wide facts, kept separate from persistence-unit settings. */
public record HibernateApplicationFacts(
        List<String> activeProfiles,
        OpenInView openInView,
        Boolean deferredDatasourceInitialization,
        Boolean sqlLoggerEnabled,
        Boolean bindLoggerEnabled,
        boolean panacheEnhancementVerified) {
    public enum OpenInView {
        ENABLED,
        DISABLED,
        UNKNOWN,
        NOT_APPLICABLE
    }

    public HibernateApplicationFacts {
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        openInView = openInView == null ? OpenInView.UNKNOWN : openInView;
    }

    public static HibernateApplicationFacts unknown(List<String> profiles) {
        return new HibernateApplicationFacts(profiles, OpenInView.UNKNOWN, null, null, null, false);
    }
}
