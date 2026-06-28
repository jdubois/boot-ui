package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.BeanList;
import io.github.jdubois.bootui.engine.beans.BeansService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Beans panel ({@code GET /bootui/api/beans}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code BeansController}: a thin transport adapter over
 * the shared engine {@link BeansService}, which owns the framework-neutral sorting, classification /
 * free-text filtering and paging. The Arc/CDI bean enumeration, self-data filtering and classification
 * live in {@code QuarkusBeanProvider} (the Quarkus {@code BeanProvider} the engine service is built
 * over).</p>
 */
@Path("/bootui/api/beans")
public class BeansResource {

    private final BeansService beans;

    @Inject
    public BeansResource(BeansService beans) {
        this.beans = beans;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public BeanList beans(
            @QueryParam("q") String query,
            @QueryParam("classification") String classification,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        return beans.beans(query, classification, offset, limit);
    }
}
