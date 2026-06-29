package com.example.security;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Role-protected endpoint used to fire Quarkus authentication/authorization security events. */
@Path("/secure")
public class SecureResource {

    @GET
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_PLAIN)
    public String secret() {
        return "ok";
    }
}
