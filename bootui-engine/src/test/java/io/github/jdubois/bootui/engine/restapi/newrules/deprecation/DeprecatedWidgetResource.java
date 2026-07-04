package io.github.jdubois.bootui.engine.restapi.newrules.deprecation;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * A JAX-RS handler that pairs {@code @Deprecated} with MicroProfile OpenAPI's {@code
 * @Operation(deprecated = true)} — the correct pattern, signaling deprecation to HTTP clients through
 * the generated OpenAPI document (smallrye-openapi honors this identically to springdoc). Must PASS
 * RAPI-DOC-003.
 */
@Path("/legacy-widgets")
public class DeprecatedWidgetResource {

    @Deprecated
    @Operation(summary = "Legacy widgets", deprecated = true)
    @GET
    public String legacyWidgets() {
        return "widgets";
    }
}
