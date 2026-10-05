package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SpringHibernateStatisticsProviderTests {

    @Test
    @SuppressWarnings("unchecked")
    void enablesStatisticsOnResolvedSessionFactory() {
        ObjectProvider<EntityManagerFactory> entityManagerFactories = mock(ObjectProvider.class);
        EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);
        SessionFactory sessionFactory = mock(SessionFactory.class);
        Statistics statistics = mock(Statistics.class);
        when(entityManagerFactories.getIfAvailable()).thenReturn(entityManagerFactory);
        when(entityManagerFactory.unwrap(SessionFactory.class)).thenReturn(sessionFactory);
        when(sessionFactory.getStatistics()).thenReturn(statistics);

        new SpringHibernateStatisticsProvider(entityManagerFactories).enableStatistics();

        verify(statistics).setStatisticsEnabled(true);
    }
}
