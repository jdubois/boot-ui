package io.github.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
                "spring.docker.compose.enabled=false",
                "bootui.show-banner=false",
                "bootui.overrides-file=target/bootui-test-overrides.properties"
        })
@Testcontainers
class BootUiSampleApplicationIntegrationTests {

    private static final Path OVERRIDES_FILE = Paths.get("target/bootui-test-overrides.properties");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

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
                    .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                            .proxy(new NoProxySelector())
                            .build()))
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

    private ResponseEntity<List> getList(String path) {
        return client().get().uri(path).retrieve().toEntity(List.class);
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
    void conditionsEndpointReturnsStableDto() {
        ResponseEntity<Map> response = getMap("/bootui/api/conditions");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("positiveMatches")).isInstanceOf(List.class);
        assertThat(body.get("negativeMatches")).isInstanceOf(List.class);
        assertThat(body.get("unconditionalClasses")).isInstanceOf(List.class);
        assertThat(body.get("exclusions")).isInstanceOf(List.class);
    }

    @Test
    void startupEndpointReturnsStableDto() {
        ResponseEntity<Map> response = getMap("/bootui/api/startup");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("steps")).isInstanceOf(List.class);
    }

    @Test
    void scheduledEndpointFindsSampleTask() {
        ResponseEntity<Map> response = getMap("/bootui/api/scheduled");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("schedulingPresent")).isEqualTo(true);
        assertThat(((Number) body.get("total")).intValue()).isGreaterThan(0);
        assertThat((Iterable<?>) body.get("tasks"))
                .anySatisfy(task -> {
                    Map<?, ?> dto = (Map<?, ?>) task;
                    assertThat(dto.get("runnable")).asString().contains("EchoScheduler");
                    assertThat(dto.get("triggerType")).isIn("FIXED_RATE", "FIXED_DELAY", "CRON");
                });
    }

    @Test
    void memoryEndpointReturnsJvmMemoryReport() {
        ResponseEntity<Map> response = getMap("/bootui/api/memory");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        Map<?, ?> heap = (Map<?, ?>) body.get("heap");
        Map<?, ?> nonHeap = (Map<?, ?>) body.get("nonHeap");
        assertThat(heap.containsKey("usedBytes")).isTrue();
        assertThat(heap.containsKey("committedBytes")).isTrue();
        assertThat(heap.containsKey("usedPercent")).isTrue();
        assertThat(nonHeap.containsKey("usedBytes")).isTrue();
        assertThat(nonHeap.containsKey("committedBytes")).isTrue();
        assertThat(nonHeap.containsKey("usedPercent")).isTrue();
        assertThat(body.get("pools")).isInstanceOf(List.class);
        assertThat(body.get("jvmInputArguments")).isInstanceOf(List.class);
        assertThat(body.get("suggestedJvmOptions")).asString().contains("-Xms").contains("-Xmx");

        Map<?, ?> calculation = (Map<?, ?>) body.get("calculation");
        assertThat(calculation).isNotNull();
        assertThat(calculation.get("valid")).isEqualTo(Boolean.TRUE);
        assertThat(calculation.containsKey("totalMemoryBytes")).isTrue();
        assertThat(calculation.containsKey("heapBytes")).isTrue();
        assertThat(calculation.containsKey("metaspaceBytes")).isTrue();
        assertThat(calculation.containsKey("codeCacheBytes")).isTrue();
        assertThat(calculation.containsKey("directMemoryBytes")).isTrue();
        assertThat(calculation.containsKey("stackBytesTotal")).isTrue();
        assertThat(calculation.containsKey("headRoomBytes")).isTrue();
        assertThat(calculation.containsKey("threadCount")).isTrue();
        assertThat(calculation.containsKey("loadedClasses")).isTrue();
        assertThat(calculation.get("jvmOptions")).asString()
                .contains("-Xmx")
                .contains("-XX:MaxMetaspaceSize=")
                .contains("-XX:ReservedCodeCacheSize=");
    }

    @Test
    void profilesEndpointReturnsActiveProfileReport() {
        ResponseEntity<Map> response = getMap("/bootui/api/profiles");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(((List<?>) body.get("activeProfiles")).contains("dev")).isTrue();
        assertThat(body.get("profileSources")).isInstanceOf(List.class);
    }

    @Test
    void httpProbeEndpointCallsLoopbackSampleEndpoint() {
        ResponseEntity<Map> response = postMap("/bootui/api/probe",
                Map.of("method", "get", "path", "api/hello", "headers", Map.of("X-Ignored", "ok")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(200);
        assertThat(body.get("statusText")).isEqualTo("OK");
        assertThat(body.get("body")).isEqualTo("Hello, world");
        assertThat(body.get("error")).isNull();
    }

    @Test
    void logTailRecentEndpointReturnsSerializedLogLines() {
        ResponseEntity<List> response = getList("/bootui/api/logs/recent");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void sampleAppEndpointsRemainPublicButAdminRequiresPassword() {
        ResponseEntity<String> plainHello = getString("/api/hello");
        assertThat(plainHello.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(plainHello.getBody()).isEqualTo("Hello, world");

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
    void sampleProductsEndpointReturnsSqlInitializedProducts() {
        ResponseEntity<List> response = getList("/api/sample/products");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).extracting(product -> ((Map<?, ?>) product).get("name"))
                .contains("BootUI Starter", "Sample Console")
                .doesNotContain("Archived Prototype");
    }

    @Test
    void rootIndexPageIntroducesTheSampleApp() {
        ResponseEntity<String> response = getString("/");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("Welcome to the BootUI sample app")
                .contains("Open BootUI")
                .contains("href=\"/bootui/\"")
                .contains("GET /api/sample/products");
    }

    @Test
    void secureApiEndpointRequiresAdminRole() {
        ResponseEntity<String> secureWithoutCredentials = getString("/api/secure");
        assertThat(secureWithoutCredentials.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> secureWithDeveloperCredentials = getStringWithBasicAuth("/api/secure", "developer",
                "developer");
        assertThat(secureWithDeveloperCredentials.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> secureWithAdminCredentials = getStringWithBasicAuth("/api/secure", "admin", "admin");
        assertThat(secureWithAdminCredentials.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secureWithAdminCredentials.getBody()).isEqualTo("Secure Hello, world");
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
                    assertThat(dto.get("requestMatcher")).asString().contains("/api/secure");
                    assertThat((Iterable<?>) dto.get("filters"))
                            .anySatisfy(filter -> assertThat(filter).isEqualTo("BasicAuthenticationFilter"));
                });

        Map<?, ?> auth = (Map<?, ?>) body.get("auth");
        assertThat(auth).isNotNull();
        assertThat((Iterable<?>) auth.get("userDetailsServiceTypes"))
                .anySatisfy(type -> assertThat(type).isEqualTo(InMemoryUserDetailsManager.class.getName()));
    }

    @Test
    void securityExplainMatchesSecureApiRequest() {
        ResponseEntity<Map> response = getMap("/bootui/api/security/explain?method=GET&path=/api/secure");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("matched")).isEqualTo(true);
        assertThat(body.get("matcherDescription")).asString().contains("/api/secure");
        assertThat((Iterable<?>) body.get("filters"))
                .anySatisfy(filter -> assertThat(filter).isEqualTo("BasicAuthenticationFilter"));
    }

    @Test
    void securityEndpointsListsControllerMappingsWithRules() {
        ResponseEntity<Map> response = getMap("/bootui/api/security/endpoints");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("springSecurityPresent")).isEqualTo(true);
        assertThat(body.get("handlerMappingAvailable")).isEqualTo(true);
        Iterable<?> endpoints = (Iterable<?>) body.get("endpoints");
        assertThat(endpoints).isNotNull();

        // BootUI's own API endpoints should resolve as permitAll on the /bootui/** chain.
        assertThat(endpoints).anySatisfy(item -> {
            Map<?, ?> dto = (Map<?, ?>) item;
            if (!"/bootui/api/security".equals(dto.get("pattern"))) {
                return;
            }
            assertThat(dto.get("secured")).isEqualTo(true);
            assertThat(dto.get("rule")).isEqualTo("permitAll");
            assertThat(dto.get("chainIndex")).isEqualTo(0);
        });
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

    private static final class NoProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
        }
    }
}
