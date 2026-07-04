package io.github.jdubois.bootui.engine.restapi.jaxrs.mpopenapi;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A JAX-RS resource documented with MicroProfile OpenAPI's own {@code @Operation}/{@code @Tag}
 * annotations (as opposed to Swagger's {@code io.swagger.v3.oas.annotations} package) — the
 * framework-neutral annotation family {@code quarkus-smallrye-openapi} equally honors. See
 * https://quarkus.io/guides/openapi-swaggerui and the MicroProfile OpenAPI {@code Operation} Javadoc:
 * https://download.eclipse.org/microprofile/microprofile-open-api-3.1.1/apidocs/org/eclipse/microprofile/openapi/annotations/Operation.html
 * Proves RAPI-DOC-001/RAPI-DOC-002 recognise this annotation family, not just Swagger's.
 */
@Path("/mp-openapi-widgets")
@Tag(name = "widgets")
public class MpOpenApiWidgetResource {

    @GET
    @Operation(summary = "List widgets")
    public String list() {
        return "widgets";
    }
}
