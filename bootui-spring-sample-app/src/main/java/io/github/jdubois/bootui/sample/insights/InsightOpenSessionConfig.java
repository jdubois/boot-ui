package io.github.jdubois.bootui.sample.insights;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.PhysicalConnectionHandlingMode;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.ui.ModelMap;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Keeps one Hibernate session open for the whole request on the {@code connection-reacquisition} seed routes only, as
 * open-in-view does, but with a session that releases its connection after each transaction. Spring's JPA vendor adapter
 * makes a session hold its connection until it closes, so an application re-acquires only when it overrides that
 * ({@code hibernate.connection.handling_mode}); a seed route cannot set it per request, so it opens the session itself.
 * The sample turns open-in-view off elsewhere so its other seeds keep one session per transaction. Never copy this into
 * an application.
 */
@Configuration(proxyBeanMethods = false)
class InsightOpenSessionConfig implements WebMvcConfigurer {

    private final EntityManagerFactory entityManagerFactory;

    InsightOpenSessionConfig(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addWebRequestInterceptor(new ReleasingSessionInterceptor(entityManagerFactory))
                .addPathPatterns("/api/insights/tags/reacquire*");
    }

    private static final class ReleasingSessionInterceptor implements WebRequestInterceptor {

        private static final String OPENED = ReleasingSessionInterceptor.class.getName() + ".OPENED";

        private final EntityManagerFactory entityManagerFactory;

        ReleasingSessionInterceptor(EntityManagerFactory entityManagerFactory) {
            this.entityManagerFactory = entityManagerFactory;
        }

        @Override
        public void preHandle(WebRequest request) {
            if (TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
                return;
            }
            var session = entityManagerFactory
                    .unwrap(SessionFactory.class)
                    .withOptions()
                    .connectionHandlingMode(
                            PhysicalConnectionHandlingMode.DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION)
                    .openSession();
            TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(session));
            request.setAttribute(OPENED, Boolean.TRUE, WebRequest.SCOPE_REQUEST);
        }

        @Override
        public void postHandle(WebRequest request, ModelMap model) {}

        @Override
        public void afterCompletion(WebRequest request, Exception ex) {
            if (request.getAttribute(OPENED, WebRequest.SCOPE_REQUEST) != null
                    && TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory)
                            instanceof EntityManagerHolder holder) {
                EntityManagerFactoryUtils.closeEntityManager(holder.getEntityManager());
            }
        }
    }
}
