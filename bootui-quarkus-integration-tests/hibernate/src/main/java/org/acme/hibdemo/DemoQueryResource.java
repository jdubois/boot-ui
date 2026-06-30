package org.acme.hibdemo;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Tiny demo endpoint that issues Hibernate ORM SQL on demand, so {@code BootUiQuarkusSqlTraceOrmCaptureTest}
 * can prove the {@code BootUiHibernateStatementInspector} records ORM-issued statements into the shared SQL
 * Trace recorder. The query runs through the {@link EntityManager} (not a wrapped JDBC {@code DataSource}), so
 * a captured row demonstrates the StatementInspector path specifically — the gap the inspector closes.
 */
@Path("/demo")
public class DemoQueryResource {

    @Inject
    EntityManager em;

    @GET
    @Path("/products")
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    public String productCount() {
        Long count =
                em.createQuery("select count(p) from Product p", Long.class).getSingleResult();
        return Long.toString(count);
    }
}
