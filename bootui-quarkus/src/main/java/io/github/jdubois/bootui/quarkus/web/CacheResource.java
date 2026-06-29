package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.CacheClearRequest;
import io.github.jdubois.bootui.core.dto.CacheReport;
import io.github.jdubois.bootui.engine.cache.CacheClearResponse;
import io.github.jdubois.bootui.engine.cache.CacheService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the Cache panel ({@code GET /bootui/api/cache},
 * {@code POST /bootui/api/cache/clear}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code SpringCacheController}: a thin transport adapter
 * over the shared engine {@link CacheService}, which owns the framework-neutral metric overlay, ordering and
 * clear orchestration. The state-changing clear endpoint is gated by the shared {@code LocalhostGuard} write
 * floor enforced by {@code BootUiQuarkusSafetyFilter}; the engine maps the outcome to a
 * {@link CacheClearResponse} status that is rendered here onto a JAX-RS {@link Response}, identical to the
 * Spring {@code ResponseEntity} status.</p>
 *
 * <p>The path keeps the shared panel id {@code cache} so the route and DTO contract are identical
 * across adapters; only the rendered content adapts per platform.</p>
 */
@Path("/bootui/api/cache")
public class CacheResource {

    private final CacheService service;

    @Inject
    public CacheResource(CacheService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CacheReport cache() {
        return service.report();
    }

    @POST
    @Path("/clear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response clear(CacheClearRequest request) {
        CacheClearResponse response = service.clear(request);
        return Response.status(response.status()).entity(response.body()).build();
    }
}
