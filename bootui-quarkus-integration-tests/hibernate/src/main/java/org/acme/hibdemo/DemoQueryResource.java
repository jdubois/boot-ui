package org.acme.hibdemo;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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

    /**
     * Saves three tags, querying the tags after each one, so Hibernate auto-flushes the pending insert before every
     * query: the {@code orm} journal source's seed (M4-9).
     */
    @POST
    @Path("/tags/auto-flush")
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    public String tagsWithAutoFlush() {
        long count = 0;
        for (int i = 0; i < 3; i++) {
            Tag tag = new Tag();
            tag.setLabel("tag-" + i);
            em.persist(tag);
            count = em.createQuery("select count(t) from Tag t", Long.class).getSingleResult();
        }
        return Long.toString(count);
    }
}
