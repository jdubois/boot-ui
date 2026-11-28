package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

/**
 * JAX-RS resource for the GitHub dashboard panel ({@code GET /bootui/api/github},
 * {@code POST /bootui/api/github/refresh}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code GitHubController}: a thin transport adapter over the
 * shared engine {@link GitHubDashboardService}, which detects the host application's git repository and shapes
 * the framework-neutral {@link GitHubDashboardReport}. {@code GET} returns the cached/READY dashboard and
 * <em>never</em> calls the network; {@code POST /refresh} is the only path that issues live GitHub API calls,
 * and only when {@code bootui.github.api-enabled=true} and the API host is allow-listed. This honors the
 * "no network on render, only on the explicit refresh action" rule. The cache-on-success (and skip-cache-on
 * {@code ERROR}) and api-disabled ({@code DISABLED}) behaviors all live in the shared engine service, so they
 * are identical to Spring.</p>
 *
 * <p>It is {@code @ApplicationScoped} (not the default per-request scope) because the engine service caches
 * the last refreshed report per selected remote across requests — the CDI analogue of the Spring
 * controller's singleton holding the engine service. {@code POST /refresh} is a state-changing endpoint, so it
 * is gated by {@code BootUiQuarkusSafetyFilter} like every other write.</p>
 */
@ApplicationScoped
@Path("/bootui/api/github")
public class GitHubResource {

    private final GitHubDashboardService service;

    @Inject
    public GitHubResource(GitHubDashboardService service) {
        this.service = service;
    }

    public GitHubDashboardReport dashboard() {
        return service.dashboard();
    }

    public GitHubDashboardReport refresh() {
        return service.refresh();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public GitHubDashboardReport dashboard(@Context UriInfo uriInfo) {
        return service.dashboard(selectedRemote(uriInfo));
    }

    @POST
    @Path("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public GitHubDashboardReport refresh(@Context UriInfo uriInfo) {
        try {
            var selection = GitHubDashboardService.parseRefreshSelection(uriInfo.getQueryParameters());
            return service.refresh(selection.remote(), selection.host(), selection.repository());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage(), ex);
        }
    }

    private String selectedRemote(UriInfo uriInfo) {
        try {
            return GitHubDashboardService.parseRemoteSelection(uriInfo.getQueryParameters());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage(), ex);
        }
    }
}
