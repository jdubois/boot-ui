package io.github.jdubois.bootui.engine.hibernate;

/** Optional native adapter. Only an explicit scan invokes it. */
@FunctionalInterface
public interface HibernateAdvisorObservationSource {
    HibernateAdvisorObservation observe();
}
