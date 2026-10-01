package io.github.jdubois.bootui.engine.restapi.jaxrs.quarkusstatus;

import io.github.jdubois.bootui.engine.restapi.jaxrs.WidgetDto;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.ResponseStatus;

/**
 * Quarkus REST resource that declares its status and headers through {@code @ResponseStatus(int)} and the
 * repeatable {@code @ResponseHeader}: RAPI-RESP-001, RAPI-RESP-008 and RAPI-ERR-007 must read them exactly like
 * Spring's {@code @ResponseStatus}.
 */
@Path("/gizmos")
public class QuarkusStatusResource {

    @POST
    @ResponseStatus(201)
    public WidgetDto createGizmo(WidgetDto widget) {
        return widget;
    }

    @POST
    @Path("/located")
    @ResponseStatus(201)
    @ResponseHeader(name = "X-Trace", value = "gizmo")
    @ResponseHeader(name = "Location", value = "/gizmos/1")
    public WidgetDto createLocatedGizmo(WidgetDto widget) {
        return widget;
    }

    @POST
    @Path("/legacy")
    public WidgetDto addGizmo(WidgetDto widget) {
        return widget;
    }

    @GET
    @Path("/busy")
    @ResponseStatus(503)
    @ResponseHeader(name = "Retry-After", value = "30")
    public String busy() {
        return "busy";
    }

    @GET
    @Path("/throttled")
    @ResponseStatus(429)
    public String throttled() {
        return "slow down";
    }
}
