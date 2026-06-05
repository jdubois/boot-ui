package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GitHubApiClientTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void refreshLoadsRepositoryMetricsAndDynamicQuotaResources() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        json("/rate_limit", """
                {"resources":{"core":{"limit":5000,"used":50,"remaining":4950,"reset":1893456000},"search":{"limit":30,"used":2,"remaining":28,"reset":1893456000},"code_scanning_autofix":{"limit":10,"used":0,"remaining":10,"reset":1893456000}}}
                """);
        json("/repos/jdubois/boot-ui", """
                {"full_name":"jdubois/boot-ui","html_url":"https://github.com/jdubois/boot-ui","default_branch":"main","visibility":"public","private":false,"fork":false,"archived":false,"pushed_at":"2026-06-04T08:00:00Z","stargazers_count":12,"forks_count":3,"subscribers_count":4,"open_issues_count":7,"owner":{"type":"Organization"}}
                """);
        json("/repos/jdubois/boot-ui/pulls", """
                [{"number":42,"title":"Add dashboard","draft":false,"html_url":"https://github.com/jdubois/boot-ui/pull/42","updated_at":"2026-06-04T08:30:00Z","user":{"login":"alice"},"labels":[{"name":"feature"}]}]
                """);
        json("/repos/jdubois/boot-ui/issues", """
                [{"number":7,"title":"Bug","user":{"login":"carol"},"comments":3,"html_url":"https://github.com/jdubois/boot-ui/issues/7","created_at":"2026-03-30T08:00:00Z","updated_at":"2026-04-01T08:30:00Z","labels":[]},{"number":8,"pull_request":{},"labels":[]}]
                """);
        json("/repos/jdubois/boot-ui/actions/runs", """
                {"workflow_runs":[{"id":9,"workflow_id":100,"name":"Build","display_title":"Run tests","run_number":41,"event":"push","status":"completed","conclusion":"failure","head_branch":"main","triggering_actor":{"login":"alice"},"html_url":"https://github.com/jdubois/boot-ui/actions/runs/9","created_at":"2026-06-04T07:00:00Z","run_started_at":"2026-06-04T07:01:00Z","updated_at":"2026-06-04T07:05:00Z"},{"id":10,"workflow_id":100,"name":"Build","display_title":"Run tests","run_number":42,"event":"push","status":"completed","conclusion":"success","head_branch":"main","triggering_actor":{"login":"alice"},"html_url":"https://github.com/jdubois/boot-ui/actions/runs/10","created_at":"2026-06-04T08:00:00Z","run_started_at":"2026-06-04T08:01:00Z","updated_at":"2026-06-04T08:05:00Z"},{"id":11,"workflow_id":200,"name":"Build","display_title":"Manual release","run_number":9,"event":"workflow_dispatch","status":"completed","conclusion":"timed_out","head_branch":"main","actor":{"login":"bob"},"html_url":"https://github.com/jdubois/boot-ui/actions/runs/11","created_at":"2026-06-03T08:00:00Z","run_started_at":"2026-06-03T08:01:00Z","updated_at":"2026-06-03T08:20:00Z"},{"id":12,"workflow_id":200,"name":"Build","display_title":"Feature branch scan","run_number":8,"event":"push","status":"completed","conclusion":"failure","head_branch":"feature/github-dashboard","actor":{"login":"bob"},"html_url":"https://github.com/jdubois/boot-ui/actions/runs/12","created_at":"2026-06-02T08:00:00Z","run_started_at":"2026-06-02T08:01:00Z","updated_at":"2026-06-02T08:20:00Z"}]}
                """);
        json("/repos/jdubois/boot-ui/actions/workflows", """
                {"workflows":[{"id":100,"name":"Build","path":".github/workflows/build.yml","state":"active","html_url":"https://github.com/jdubois/boot-ui/blob/main/.github/workflows/build.yml"},{"id":200,"name":"CodeQL","path":"dynamic/github-code-scanning/codeql","state":"active","html_url":"https://github.com/jdubois/boot-ui/actions/workflows/github-code-scanning/codeql"},{"id":300,"name":"Native image","path":".github/workflows/native.yml","state":"active","html_url":"https://github.com/jdubois/boot-ui/blob/main/.github/workflows/native.yml"}]}
                """);
        json("/repos/jdubois/boot-ui/dependabot/alerts", jsonArray(40));
        json("/repos/jdubois/boot-ui/code-scanning/alerts", "[]");
        json("/repos/jdubois/boot-ui/secret-scanning/alerts", "[]");
        json("/repos/jdubois/boot-ui/actions/cache/usage", "{\"active_caches_size_in_bytes\":2048}");
        json("/repos/jdubois/boot-ui/actions/artifacts", "{\"total_count\":5}");
        json("/orgs/jdubois/settings/billing/actions", "{\"included_minutes\":2000,\"total_minutes_used\":125}");
        jsonWithApiVersion("/orgs/jdubois/copilot/metrics/reports/organization-28-day/latest", "2026-03-10", """
                        {"report_start_day":"2026-05-07","report_end_day":"2026-06-03","download_links":["https://signed.example/report.ndjson"]}
                        """);
        server.start();

        GitHubApiClient client = client("local-token");

        GitHubDashboardReport report = client.refresh(repository());

        assertThat(report.status()).isEqualTo("CONNECTED");
        assertThat(report.connected()).isTrue();
        assertThat(report.credential().source()).isEqualTo("test-token");
        assertThat(report.repository().defaultBranch()).isEqualTo("main");
        assertThat(report.pullRequests()).singleElement().extracting("number").isEqualTo(42);
        assertThat(report.workflowRuns())
                .filteredOn(run -> run.id() == 11)
                .singleElement()
                .extracting("conclusion")
                .isEqualTo("timed_out");
        assertThat(report.workflowRuns())
                .filteredOn(run -> run.id() == 10)
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.workflowId()).isEqualTo(100L);
                    assertThat(run.displayTitle()).isEqualTo("Run tests");
                    assertThat(run.runNumber()).isEqualTo(42L);
                    assertThat(run.actor()).isEqualTo("alice");
                });
        assertThat(report.workflows()).extracting("name").containsExactly("Build", "CodeQL", "Native image");
        assertThat(report.workflows())
                .filteredOn(workflow -> workflow.id() == 100)
                .singleElement()
                .satisfies(workflow -> {
                    assertThat(workflow.htmlUrl())
                            .isEqualTo("https://github.com/jdubois/boot-ui/actions/workflows/build.yml");
                    assertThat(workflow.latestRun()).isNotNull();
                    assertThat(workflow.latestRun().id()).isEqualTo(10);
                });
        assertThat(report.workflows())
                .filteredOn(workflow -> workflow.id() == 200)
                .singleElement()
                .satisfies(workflow -> {
                    assertThat(workflow.htmlUrl())
                            .isEqualTo(
                                    "https://github.com/jdubois/boot-ui/actions/workflows/github-code-scanning/codeql");
                    assertThat(workflow.latestRun()).isNotNull();
                    assertThat(workflow.latestRun().id()).isEqualTo(11);
                });
        assertThat(report.workflows())
                .filteredOn(workflow -> workflow.id() == 300)
                .singleElement()
                .extracting("latestRun")
                .isNull();
        assertThat(report.issueBuckets())
                .filteredOn(bucket -> bucket.label().equals("Stale 30d+"))
                .singleElement()
                .extracting("count")
                .isEqualTo(1);
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.number()).isEqualTo(7);
            assertThat(issue.title()).isEqualTo("Bug");
            assertThat(issue.author()).isEqualTo("carol");
            assertThat(issue.comments()).isEqualTo(3);
            assertThat(issue.htmlUrl()).isEqualTo("https://github.com/jdubois/boot-ui/issues/7");
        });
        assertThat(report.quotas())
                .extracting("key")
                .contains(
                        "core",
                        "search",
                        "code_scanning_autofix",
                        "actions-cache",
                        "actions-artifacts",
                        "actions-minutes");
        assertThat(report.quotas())
                .filteredOn(quota -> quota.key().equals("code_scanning_autofix"))
                .singleElement()
                .extracting("status")
                .isEqualTo("OK");
        assertThat(report.metrics())
                .filteredOn(metric -> metric.label().equals("Workflow failures"))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.value()).isEqualTo("2");
                    assertThat(metric.detail()).isEqualTo("Latest run per workflow/branch");
                });
        assertThat(report.metrics())
                .filteredOn(metric -> metric.label().equals("Copilot usage"))
                .singleElement()
                .extracting("value")
                .isEqualTo("Available");
        assertThat(report.copilotUsage().status()).isEqualTo("AVAILABLE");
        assertThat(report.copilotUsage().downloadLinkCount()).isEqualTo(1);
        assertThat(report.copilotUsage().summary()).doesNotContain("signed.example");
        assertThat(report.securitySignals())
                .filteredOn(signal -> signal.label().equals("Dependabot alerts"))
                .singleElement()
                .extracting("count")
                .isEqualTo(40);
        assertThat(report.securitySignals())
                .filteredOn(signal -> signal.label().equals("Code scanning alerts"))
                .singleElement()
                .extracting("count")
                .isEqualTo(0);
        assertThat(report.securitySignals())
                .filteredOn(signal -> signal.label().equals("Secret scanning alerts"))
                .singleElement()
                .extracting("count")
                .isEqualTo(0);
    }

    @Test
    void skipsOptionalCallsWhenCoreQuotaIsNearSafetyThreshold() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        json("/rate_limit", """
                {"resources":{"core":{"limit":60,"used":59,"remaining":1,"reset":1893456000}}}
                """);
        server.start();

        GitHubApiClient client = client(null);

        GitHubDashboardReport report = client.refresh(repository());

        assertThat(report.status()).isEqualTo("QUOTA_PROTECTED");
        assertThat(report.pullRequests()).isEmpty();
        assertThat(report.quotas()).extracting("key").containsExactly("core");
        assertThat(report.warnings()).singleElement().asString().contains("safety threshold");
    }

    private GitHubApiClient client(String token) {
        BootUiProperties.GitHub properties = new BootUiProperties.GitHub();
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setAllowedApiHosts(new String[] {"localhost"});
        return new GitHubApiClient(
                properties,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                timeout -> token == null ? null : new GitHubTokenProvider.Token(token, "test-token"));
    }

    private GitHubRepositoryDetector.Repository repository() {
        return new GitHubRepositoryDetector.Repository(
                "jdubois",
                "boot-ui",
                "jdubois/boot-ui",
                "github.com",
                URI.create("http://localhost:" + server.getAddress().getPort() + "/"),
                "https://github.com/jdubois/boot-ui",
                "main",
                "main");
    }

    private void json(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-OAuth-Scopes", "repo, workflow");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private String jsonArray(int count) {
        if (count <= 0) {
            return "[]";
        }
        return "[" + "{},".repeat(count - 1) + "{}]";
    }

    private void jsonWithApiVersion(String path, String expectedApiVersion, String body) {
        server.createContext(path, exchange -> {
            String apiVersion = exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (!expectedApiVersion.equals(apiVersion)) {
                exchange.sendResponseHeaders(400, 0);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
    }
}
