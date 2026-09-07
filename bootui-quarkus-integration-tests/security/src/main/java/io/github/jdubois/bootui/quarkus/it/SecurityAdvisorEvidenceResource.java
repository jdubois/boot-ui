package io.github.jdubois.bootui.quarkus.it;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

@Path("/advisor-policy")
@DenyAll
public class SecurityAdvisorEvidenceResource {
    @GET
    @Path("/exact")
    @PermitAll
    public String exact() {
        return "exact";
    }

    @GET
    @Path("/exact/child")
    @PermitAll
    public String child() {
        return "child";
    }

    @GET
    @Path("/method")
    @PermitAll
    @Produces("text/html")
    public String get() {
        return "<p>Public document</p>";
    }

    @POST
    @Path("/method")
    @PermitAll
    public String post() {
        return "post";
    }

    @GET
    @Path("/denied")
    public String denied() {
        return "denied";
    }

    @GET
    @Path("/scopes/open")
    @PermitAll
    public String scoped() {
        return "not reachable through the HTTP deny";
    }

    @GET
    @Path("/rest-scopes/open")
    @PermitAll
    public String restScoped() {
        return "not reachable through the REST deny";
    }
}
