package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.config.Customizer.withDefaults;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = BootUiSpringSecurityAutoConfigurationTests.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "bootui.enabled=ON")
@ExtendWith(OutputCaptureExtension.class)
class BootUiSpringSecurityAutoConfigurationTests {

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("bootUiSecurityFilterChain")
    private SecurityFilterChain bootUiSecurityFilterChain;

    private RestClient client;

    @Test
    void permitsBootUiRouteWhenApplicationSecurityRequiresAuthentication() {
        ResponseEntity<String> bootUi =
                client().get().uri("/bootui/api/overview").retrieve().toEntity(String.class);
        assertThat(bootUi.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> protectedRoute =
                client().get().uri("/protected").retrieve().toEntity(String.class);
        assertThat(protectedRoute.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void permitsMcpJsonRpcPostWithoutCsrfToken() {
        // Non-browser MCP clients (e.g. VS Code) cannot present the SPA CSRF token; a 403 here would
        // make them fall back to an OAuth flow ("Dynamic Client Registration not supported").
        ResponseEntity<String> mcp = client().post()
                .uri("/bootui/api/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(mcp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void permitsAuthenticationSessionPostWithoutCsrfToken() {
        ResponseEntity<Void> session =
                client().post().uri("/bootui/api/auth/session").retrieve().toBodilessEntity();

        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void bootUiSecurityChainMatchesExactRootsAndDescendants() {
        assertThat(bootUiSecurityFilterChain.matches(request("/bootui"))).isTrue();
        assertThat(bootUiSecurityFilterChain.matches(request("/bootui/"))).isTrue();
        assertThat(bootUiSecurityFilterChain.matches(request("/bootui/api"))).isTrue();
        assertThat(bootUiSecurityFilterChain.matches(request("/bootui/api/overview")))
                .isTrue();
        assertThat(bootUiSecurityFilterChain.matches(request("/protected"))).isFalse();
    }

    @Test
    void logsWarningWhenOpeningBootUiRoute(CapturedOutput output) {
        assertThat(output)
                .contains(
                        "BootUI detected Spring Security and is permitting unauthenticated access to /bootui, /bootui/**");
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private RestClient client() {
        if (client == null) {
            client = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
                    .build();
        }
        return client;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableWebSecurity
    @Import(TestApplication.ProtectedController.class)
    static class TestApplication {

        @Bean
        SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(
                            authorize -> authorize.anyRequest().authenticated())
                    .httpBasic(withDefaults())
                    .build();
        }

        @RestController
        static class ProtectedController {

            @GetMapping("/protected")
            String protectedEndpoint() {
                return "protected";
            }
        }
    }
}
