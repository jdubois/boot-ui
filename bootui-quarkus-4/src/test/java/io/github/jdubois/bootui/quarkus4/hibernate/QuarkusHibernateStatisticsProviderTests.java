package io.github.jdubois.bootui.quarkus4.hibernate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

class QuarkusHibernateStatisticsProviderTests {

    @Test
    @SuppressWarnings("unchecked")
    void enablesStatisticsOnResolvedSessionFactory() {
        Instance<EntityManagerFactory> entityManagerFactories = mock(Instance.class);
        EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);
        SessionFactory sessionFactory = mock(SessionFactory.class);
        Statistics statistics = mock(Statistics.class);
        when(entityManagerFactories.isUnsatisfied()).thenReturn(false);
        when(entityManagerFactories.iterator())
                .thenReturn(List.of(entityManagerFactory).iterator());
        when(entityManagerFactory.unwrap(SessionFactory.class)).thenReturn(sessionFactory);
        when(sessionFactory.getStatistics()).thenReturn(statistics);

        new QuarkusHibernateStatisticsProvider(entityManagerFactories).enableStatistics();

        verify(statistics).setStatisticsEnabled(true);
    }
}
