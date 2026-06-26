package io.github.jdubois.bootui.quarkus.sample.web;

import io.github.jdubois.bootui.quarkus.sample.catalog.CatalogService;
import io.github.jdubois.bootui.quarkus.sample.catalog.ProductSummary;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Secured endpoints (role {@code admin}). A secured request that also runs a live SQL SELECT lets the
 * BootUI Live Activity profiler correlate a security event with the SQL it executed.
 */
@Path("/api/secure")
@RolesAllowed("admin")
public class SecureResource {

    @Inject
    CatalogService catalog;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String secure() {
        return "Secure Hello, world";
    }

    @GET
    @Path("/products")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ProductSummary> secureProducts() {
        return catalog.securedCatalog();
    }
}
