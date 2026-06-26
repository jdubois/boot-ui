package io.github.jdubois.bootui.quarkus.sample.web;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Admin-only endpoint (role {@code admin}), mirrors the Spring sample's AdminController. */
@Path("/admin")
@RolesAllowed("admin")
@Produces(MediaType.TEXT_PLAIN)
public class AdminResource {

    @GET
    public String admin() {
        return "BootUI sample admin";
    }
}
