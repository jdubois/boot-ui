package io.github.jdubois.bootui.quarkus4.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.github.GitHubDashboardConfig;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class GitHubResourceTest {

    @TempDir
    Path project;

    @Test
    void explicitSelectionSerializesSafeContextWithoutNetworkOrChangingTheDefault() throws Exception {
        GitHubResource resource = resource();

        assertThat(resource.dashboard().repository().fullName()).isEqualTo("fork/service");
        var upstream = resource.dashboard(selection("upstream"));
        assertThat(upstream.repository().host()).isEqualTo("ghe.example.com");
        assertThat(upstream.repositoryContext().selectedRemote()).isEqualTo("upstream");
        assertThat(upstream.repository().fork()).isNull();
        String json = new ObjectMapper().writeValueAsString(upstream);
        assertThat(json).contains("\"selectedRemote\":\"upstream\"", "\"fullName\":\"team/service\"");
        assertThat(json).doesNotContain("embedded-credential", "user:", "file:");
        assertThat(resource.dashboard().repositoryContext().selectedRemote()).isEqualTo("origin");
        assertThat(resource.refresh(selection("upstream")).status()).isEqualTo("DISABLED");
        assertThat(resource.dashboard(selection("upstream")).status()).isEqualTo("DISABLED");
        assertThat(resource.dashboard().status()).isEqualTo("READY");
    }

    @Test
    void arbitraryAndEmptyTargetsAreRejected() throws Exception {
        GitHubResource resource = resource();
        for (String remote : List.of("", "other", "file:///tmp/owner/repo", "https://github.com/owner/repo")) {
            assertThatThrownBy(() -> resource.dashboard(selection(remote)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("GitHub remote must be origin or upstream");
            assertThatThrownBy(() -> resource.refresh(selection(remote)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("GitHub remote must be origin or upstream");
        }
    }

    @Test
    void missingUpstreamIsUnknownAndNeverFallsBackToOrigin() throws Exception {
        GitHubResource resource = resource();
        Files.writeString(project.resolve(".git/config"), """
                [remote "origin"]
                    url = https://github.com/fork/service.git
                """);

        var report = resource.refresh(selection("upstream"));
        assertThat(report.status()).isEqualTo("UNAVAILABLE");
        assertThat(report.repository()).isNull();
        assertThat(report.repositoryContext().upstream()).isNull();
        assertThat(report.repositoryContext().upstreamUnavailableReason()).contains("unknown");
        assertThat(resource.dashboard().repository().fullName()).isEqualTo("fork/service");
    }

    private UriInfo selection(String remote) {
        UriInfo uriInfo = mock(UriInfo.class);
        var queryParameters = new MultivaluedHashMap<String, String>();
        queryParameters.putSingle("remote", remote);
        when(uriInfo.getQueryParameters()).thenReturn(queryParameters);
        return uriInfo;
    }

    private GitHubResource resource() throws Exception {
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve(".git/config"), """
                [remote "origin"]
                    url = https://github.com/fork/service.git
                [remote "upstream"]
                    url = https://user:embedded-credential@ghe.example.com/team/service.git
                """);
        return new GitHubResource(GitHubDashboardService.using(
                project, new GitHubDashboardConfig(false, List.of("ghe.example.com")), repository -> {
                    throw new AssertionError("This resource test must never call the network");
                }));
    }
}
