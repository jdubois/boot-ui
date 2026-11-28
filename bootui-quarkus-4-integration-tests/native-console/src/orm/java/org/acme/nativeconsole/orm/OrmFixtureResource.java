package org.acme.nativeconsole.orm;

import io.quarkus.hibernate.orm.PersistenceUnit;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/native-fixture/orm")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class OrmFixtureResource {

    @Inject
    EntityManager entityManager;

    @Inject
    @PersistenceUnit("users")
    EntityManager users;

    @GET
    @Transactional
    public Map<String, Object> read() {
        return Map.of(
                "unit",
                "default",
                "entities",
                entityManager
                        .createQuery("select count(w) from NativeOrmWitness w", Long.class)
                        .getSingleResult());
    }

    @GET
    @Path("/users")
    @Transactional
    public Map<String, Object> users() {
        long before = HostSessionListener.statements();
        long entities = users.createQuery("select count(w) from NativeOrmWitness w", Long.class)
                .getSingleResult();
        return Map.of(
                "unit",
                "users",
                "entities",
                entities,
                "hostListenerInvoked",
                HostSessionListener.statements() > before);
    }
}
