package org.acme.nativeconsole;

import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Map;

/**
 * Test support only, never part of a published adapter or the sample. These routes neither expose nor
 * override BootUI's activation decision; the flag mutation only tests whether that decision froze.
 */
@Path("/native-fixture")
@Produces(MediaType.APPLICATION_JSON)
public class FixtureResource {

    @Inject
    WitnessService service;

    @Inject
    DormancyProbe probe;

    @Context
    RoutingContext routing;

    @GET
    @Path("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "application", "bootui-native-acceptance");
    }

    @GET
    @Path("/witness")
    public WitnessService.Witness witness() {
        return service.witness();
    }

    @GET
    @Path("/jdbc")
    @Blocking
    public WitnessService.JdbcResult jdbc() throws SQLException {
        return service.jdbc();
    }

    @GET
    @Path("/failure")
    public Map<String, String> failure() {
        service.failure();
        throw new IllegalStateException("The deliberate host failure unexpectedly returned");
    }

    @GET
    @Path("/probe")
    public DormancyProbe.Snapshot snapshot() throws UnknownHostException {
        localProbeOnly();
        routing.response().putHeader("X-Native-Fixture-Private", "fixture-only");
        routing.response().putHeader("Set-Cookie", "native-acceptance-private=fixture-only; HttpOnly; SameSite=Strict");
        return probe.snapshot();
    }

    @POST
    @Path("/flags/{selection}")
    public Map<String, String> flags(@PathParam("selection") String selection) throws UnknownHostException {
        localProbeOnly();
        switch (selection) {
            case "on-local" -> {
                System.setProperty("bootui.enabled", "ON");
                System.setProperty("quarkus.profile", "local");
            }
            case "off-production" -> {
                System.setProperty("bootui.enabled", "OFF");
                System.setProperty("quarkus.profile", "production");
            }
            default -> throw new WebApplicationException(400);
        }
        return Map.of("selection", selection);
    }

    @POST
    @Path("/slow-config")
    public Map<String, String> slowConfig() throws UnknownHostException {
        localProbeOnly();
        SlowConfigSource.arm();
        return Map.of("operation", "armed");
    }

    private void localProbeOnly() throws UnknownHostException {
        String address = routing.request().connection().remoteAddress().hostAddress();
        var requestAuthority = routing.request().authority();
        String authority = requestAuthority == null ? null : requestAuthority.toString();
        if (!InetAddress.getByName(address).isLoopbackAddress()
                || authority == null
                || !(authority.startsWith("127.0.0.1:") || authority.startsWith("localhost:"))
                || routing.request().getHeader("Origin") != null
                || "cross-site".equalsIgnoreCase(routing.request().getHeader("Sec-Fetch-Site"))) {
            throw new WebApplicationException(403);
        }
    }
}
