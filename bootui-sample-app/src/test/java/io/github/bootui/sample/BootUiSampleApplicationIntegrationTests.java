package io.github.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.client.RestClient;

/**
 * End-to-end tests that boot the sample app on a random port and call BootUI's
 * REST API through HTTP, exercising auto-configuration, the localhost-only filter
 * for loopback callers, and the override persistence path.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=dev",
                "bootui.show-banner=false",
                "bootui.overrides-file=target/bootui-test-overrides.properties"
        })
class BootUiSampleApplicationIntegrationTests {

    private static final Path OVERRIDES_FILE = Paths.get("target/bootui-test-overrides.properties");

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeAll
    static void clearLeftoverOverridesFile() throws Exception {
        Files.deleteIfExists(OVERRIDES_FILE);
    }

    @AfterAll
    static void removeOverridesFile() throws Exception {
        Files.deleteIfExists(OVERRIDES_FILE);
    }

    @AfterEach
    void cleanOverrides() throws Exception {
        Files.deleteIfExists(OVERRIDES_FILE);
    }

    private RestClient client() {
        if (client == null) {
            client = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    // Never throw on non-2xx — tests inspect the status directly.
                    .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                    .build();
        }
        return client;
    }

    private ResponseEntity<Map> getMap(String path) {
        return client().get().uri(path).retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> postMap(String path, Object body) {
        return client().post().uri(path).body(body).retrieve().toEntity(Map.class);
    }

    private ResponseEntity<String> getString(String path) {
        return client().get().uri(path).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> getStringWithBasicAuth(String path, String username, String password) {
        return client().get()
                .uri(path)
                .headers(headers -> headers.setBasicAuth(username, password))
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    void overviewEndpointReturnsActivationMetadata() {
        ResponseEntity<Map> response = getMap("/bootui/api/overview");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("applicationName")).isEqualTo("bootui-sample");
        assertThat(body.get("webApplicationType")).isEqualTo("SERVLET");
        Map<?, ?> activation = (Map<?, ?>) body.get("activation");
        assertThat(activation).isNotNull();
        assertThat(activation.get("enabled")).isEqualTo(true);
        assertThat(activation.get("localhostOnly")).isEqualTo(true);
    }

    @Test
    void configEndpointListsPropertiesAndMasksSecrets() {
        postMap("/bootui/api/config/overrides",
                Map.of("name", "demo.api.token", "value", "topsecret"));

        ResponseEntity<Map> response = getMap("/bootui/api/config");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Iterable<?>) body.get("sources"))
                .anyMatch(s -> "bootui-overrides".equals(s));

        boolean found = false;
        for (Object p : (Iterable<?>) body.get("properties")) {
            Map<?, ?> dto = (Map<?, ?>) p;
            if ("demo.api.token".equals(dto.get("name"))) {
                found = true;
                assertThat(dto.get("value")).isEqualTo("******");
                assertThat(dto.get("masked")).isEqualTo(true);
                assertThat(dto.get("override")).isEqualTo(true);
            }
        }
        assertThat(found).as("override property in response").isTrue();
    }

    @Test
    void healthEndpointReturnsStatus() {
        ResponseEntity<Map> response = getMap("/bootui/api/health");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isIn("UP", "DOWN", "UNKNOWN", "OUT_OF_SERVICE");
    }

    @Test
    void loggersEndpointExposesKnownLoggers() {
        ResponseEntity<Map> response = getMap("/bootui/api/loggers");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Iterable<Object>) body.get("availableLevels")).contains("INFO", "DEBUG");
        assertThat((Iterable<?>) body.get("loggers")).isNotEmpty();
    }

    @Test
    void postLoggerLevelChangesEffectiveLevel() {
        ResponseEntity<Map> response = postMap(
                "/bootui/api/loggers/io.github.bootui.sample",
                Map.of("level", "WARN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("configuredLevel")).isEqualTo("WARN");
        assertThat(body.get("effectiveLevel")).isEqualTo("WARN");
    }

    @Test
    void invalidLoggerLevelReturnsBadRequest() {
        ResponseEntity<Map> response = postMap(
                "/bootui/api/loggers/io.github.bootui.sample",
                Map.of("level", "NOT-A-LEVEL"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void configOverrideRoundtripPersistsAndDeletes() throws Exception {
        ResponseEntity<Map> put = postMap("/bootui/api/config/overrides",
                Map.of("name", "sample.greeting", "value", "Hola"));

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> putBody = put.getBody();
        assertThat(putBody).isNotNull();
        assertThat(putBody.get("value")).isEqualTo("Hola");
        assertThat((String) putBody.get("message")).containsIgnoringCase("restart");

        assertThat(Files.exists(OVERRIDES_FILE)).isTrue();
        assertThat(Files.readString(OVERRIDES_FILE)).contains("sample.greeting=Hola");

        ResponseEntity<Map> delete = client().delete()
                .uri("/bootui/api/config/overrides/sample.greeting")
                .retrieve()
                .toEntity(Map.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> deleteBody = delete.getBody();
        assertThat(deleteBody).isNotNull();
        assertThat(deleteBody.get("previousValue")).isEqualTo("Hola");
        assertThat(Files.readString(OVERRIDES_FILE)).doesNotContain("sample.greeting=Hola");
    }

    @Test
    void beansEndpointReturnsBeanList() {
        ResponseEntity<Map> response = getMap("/bootui/api/beans");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Integer) body.get("total")).isGreaterThan(0);
    }

    @Test
    void mappingsEndpointReturnsContexts() {
        ResponseEntity<Map> response = getMap("/bootui/api/mappings");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        // Mappings controller returns the raw ApplicationMappingsDescriptor.
        assertThat(body.containsKey("contexts")).isTrue();
    }

    @Test
    void sampleAppEndpointsRemainPublicButAdminRequiresPassword() {
        ResponseEntity<String> hello = getString("/api/sample/hello");
        assertThat(hello.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hello.getBody()).contains("Hello, BootUI!");

        ResponseEntity<String> adminWithoutCredentials = getString("/admin");
        assertThat(adminWithoutCredentials.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> adminWithDeveloperCredentials = getStringWithBasicAuth("/admin", "developer", "developer");
        assertThat(adminWithDeveloperCredentials.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> adminWithAdminCredentials = getStringWithBasicAuth("/admin", "admin", "admin");
        assertThat(adminWithAdminCredentials.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminWithAdminCredentials.getBody()).isEqualTo("BootUI sample admin");
    }

    @Test
    void dataEndpointFindsSampleJpaRepository() {
        ResponseEntity<Map> response = getMap("/bootui/api/data/repositories");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("springDataPresent")).isEqualTo(true);
        assertThat(((Number) body.get("total")).intValue()).isGreaterThan(0);
        assertThat((Iterable<?>) body.get("repositories"))
                .anySatisfy(repository -> {
                    Map<?, ?> dto = (Map<?, ?>) repository;
                    assertThat(dto.get("repositoryInterface")).isEqualTo(ProductRepository.class.getName());
                    assertThat(dto.get("domainType")).isEqualTo(Product.class.getName());
                    assertThat(dto.get("storeModule")).isEqualTo("JPA");
                    assertThat(((Number) dto.get("queryMethodCount")).intValue()).isGreaterThan(0);
                });
    }

    @Test
    void dataRepositoryDetailIncludesAnnotatedQueryMethod() {
        ResponseEntity<Map> response = getMap("/bootui/api/data/repositories/" + ProductRepository.class.getName());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Iterable<?>) body.get("methods"))
                .anySatisfy(method -> {
                    Map<?, ?> dto = (Map<?, ?>) method;
                    assertThat(dto.get("name")).isEqualTo("searchByName");
                    assertThat(dto.get("origin")).isEqualTo("ANNOTATED");
                    assertThat((String) dto.get("query")).contains("select p from Product p");
                });
    }

    @Test
    void securityEndpointFindsSampleFilterChains() {
        ResponseEntity<Map> response = getMap("/bootui/api/security");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("springSecurityPresent")).isEqualTo(true);
        assertThat((Iterable<?>) body.get("chains"))
                .anySatisfy(chain -> {
                    Map<?, ?> dto = (Map<?, ?>) chain;
                    assertThat(dto.get("requestMatcher")).asString().contains("/admin/**");
                    assertThat((Iterable<?>) dto.get("filters"))
                            .anySatisfy(filter -> assertThat(filter).isEqualTo("BasicAuthenticationFilter"));
                });

        Map<?, ?> auth = (Map<?, ?>) body.get("auth");
        assertThat(auth).isNotNull();
        assertThat((Iterable<?>) auth.get("userDetailsServiceTypes"))
                .anySatisfy(type -> assertThat(type).isEqualTo(InMemoryUserDetailsManager.class.getName()));
    }

    @Test
    void securityExplainMatchesAdminRequest() {
        ResponseEntity<Map> response = getMap("/bootui/api/security/explain?method=GET&path=/admin");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("matched")).isEqualTo(true);
        assertThat(body.get("matcherDescription")).asString().contains("/admin/**");
        assertThat((Iterable<?>) body.get("filters"))
                .anySatisfy(filter -> assertThat(filter).isEqualTo("BasicAuthenticationFilter"));
    }

    @Test
    void bootUiSpaIndexIsServed() {
        ResponseEntity<String> response = client().get().uri("/bootui/")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The bundled Vue index.html is served from bootui-ui's META-INF/resources.
        assertThat(response.getBody()).contains("<html");
    }
}
