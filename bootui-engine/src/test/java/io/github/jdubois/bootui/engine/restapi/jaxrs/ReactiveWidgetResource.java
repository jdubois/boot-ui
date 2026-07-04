package io.github.jdubois.bootui.engine.restapi.jaxrs;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * A Quarkus-idiomatic reactive JAX-RS resource using SmallRye Mutiny's {@code Uni}/{@code Multi}
 * and RESTEasy Reactive's typed {@code RestResponse<T>} — the officially-promoted reactive return
 * types for Quarkus REST resources (see the Quarkus REST guide's reactive section:
 * https://quarkus.io/guides/rest#reactive). The model builder must unwrap these to the real body
 * type exactly like it already does for Spring WebFlux's {@code Mono}/{@code Flux}, or the
 * DTO/pagination/naming rules silently blind themselves on the majority-idiomatic Quarkus reactive
 * style.
 */
@Path("/reactive-widgets")
public class ReactiveWidgetResource {

    @GET
    public Uni<WidgetDto> getOne() {
        return Uni.createFrom().item(new WidgetDto("1", "w"));
    }

    @GET
    @Path("/all")
    public Multi<WidgetDto> getAll() {
        return Multi.createFrom().item(new WidgetDto("1", "w"));
    }

    @GET
    @Path("/typed")
    public RestResponse<WidgetDto> getTyped() {
        return RestResponse.ok(new WidgetDto("1", "w"));
    }
}
