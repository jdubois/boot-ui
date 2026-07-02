package io.github.jdubois.bootui.sample.advisor.architecture;

import io.github.jdubois.bootui.sample.catalog.ProductRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Intentionally contains issues the Architecture advisor (framework-agnostic, e.g. ARCH-CODE-001 and
 * ARCH-CODE-016) and the forthcoming Quarkus advisor flag. Mirrors the Spring sample's {@code
 * ArchitectureIssuesController}.
 */
@Path("/api/architecture")
@Produces(MediaType.TEXT_PLAIN)
public class ArchitectureIssuesResource {

    // This should trigger ARCH-CODE-016 (standard-annotation field injection); the Spring-only
    // ARCH-SPRING-001 does not fire here since @Inject is CDI's own idiomatic injection annotation.
    @Inject
    ProductRepository repository;

    @GET
    @Path("/some-errors")
    public String errors() {
        // This should trigger ARCH-CODE-001 (System.out usage).
        System.out.println("This should trigger ARCH-CODE-001");
        return "";
    }
}
