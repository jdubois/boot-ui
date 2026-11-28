package io.github.jdubois.bootui.sample.sideeffects;

import jakarta.annotation.Priority;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import java.util.Date;
import java.util.Map;

/**
 * The thread-locals sensor's seeded routes ({@code docs/PLAN-v2.md} §5.16, M5-5f): blocking resource methods, on
 * Quarkus' pooled workers. With the BootUI agent's {@code thread-locals} sensor, {@code GET /api/thread-locals/leak}
 * leaves {@link TenantContext#CURRENT} set on its worker; its counterexamples, {@code cleared} (removed in {@code
 * finally}), {@code nulled} (set to {@code null}), and {@code before} (set by a filter ahead of BootUI's scope), never
 * appear; {@code cache} fills a {@code withInitial} date format, reported with its initial value flagged.
 */
@Path("/api/thread-locals")
@Produces(MediaType.APPLICATION_JSON)
public class ThreadLocalsResource {

    @GET
    @Path("/leak")
    public Map<String, Object> leak(@QueryParam("tenant") @DefaultValue("acme") String tenant) {
        TenantContext.CURRENT.set("tenant-secret-" + tenant);
        return Map.of("virtual", TenantContext.virtual());
    }

    @GET
    @Path("/cleared")
    public Map<String, Object> cleared(@QueryParam("tenant") @DefaultValue("acme") String tenant) {
        TenantContext.CURRENT.set("tenant-secret-" + tenant);
        try {
            return Map.of("virtual", TenantContext.virtual());
        } finally {
            TenantContext.CURRENT.remove();
        }
    }

    @GET
    @Path("/nulled")
    public Map<String, Object> nulled() {
        TenantContext.CURRENT.set("tenant-secret-nulled");
        TenantContext.CURRENT.set(null);
        return Map.of("virtual", TenantContext.virtual());
    }

    @GET
    @Path("/cache")
    public Map<String, Object> cache() {
        return Map.of("day", TenantContext.FORMAT.get().format(new Date(0L)), "virtual", TenantContext.virtual());
    }

    @GET
    @Path("/before")
    public Map<String, Object> before() {
        return Map.of("virtual", TenantContext.virtual());
    }

    /** Sets {@link TenantContext#BEFORE} ahead of BootUI's scope on the worker, and never clears it. */
    @Provider
    @Priority(Integer.MIN_VALUE)
    public static class BeforeFilter implements ContainerRequestFilter {

        @Override
        public void filter(ContainerRequestContext request) {
            if (request.getUriInfo().getPath().endsWith("/thread-locals/before")) {
                TenantContext.BEFORE.set("tenant-secret-before");
            }
        }
    }
}
