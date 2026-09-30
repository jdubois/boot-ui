package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.sample.catalog.Product;
import io.github.jdubois.bootui.sample.catalog.ProductRepository;
import java.net.CookieManager;
import java.net.HttpCookie;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.client.RestClient;

/**
 * End-to-end tests that boot the sample app on a random port and call BootUI's
 * REST API through HTTP, exercising auto-configuration, the localhost-only filter
 * for loopback callers, and the override persistence path.
 *
 * <p>Runs Docker-free against the {@code dev} profile's in-memory H2 database (the
 * assertions are database-vendor-agnostic), so it exercises the same H2-backed
 * JPA / Flyway / Liquibase wiring the sample app uses by default. A distinct
 * in-memory database name keeps it isolated from {@link BootUiSampleApplicationDevProfileTests},
 * which shares the same JVM during the module's test run.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:bootui_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "spring.cache.type=simple",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration,"
                    + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                    + "org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration,"
                    + "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration,"
                    + "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration,"
                    + "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-test-overrides.properties"
        })
class BootUiSampleApplicationIntegrationTests {

    private static final Path OVERRIDES_FILE = Paths.get("target/bootui-test-overrides.properties");

    @LocalServerPort
    int port;

    private final CookieManager cookieManager = new CookieManager();

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
                            .cookieHandler(cookieManager)
                            .proxy(new NoProxySelector())
                            .build()))
                    // Never throw on non-2xx — tests inspect the status directly.
                    .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
                    .build();
        }
        return client;
    }

    private ResponseEntity<Map<String, Object>> getMap(String path) {
        return client().get().uri(path).retrieve().toEntity(new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> postMap(String path, Object body) {
        return client().post()
                .uri(path)
                .headers(headers -> applyCsrfToken(path, headers))
                .body(body)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<List<Object>> getList(String path) {
        return client().get().uri(path).retrieve().toEntity(new ParameterizedTypeReference<>() {});
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

    private void applyCsrfToken(String path, HttpHeaders headers) {
        if (path.startsWith("/bootui/")) {
            headers.set("X-XSRF-TOKEN", csrfToken());
        }
    }

    private String csrfToken() {
        client().get().uri("/bootui/api/overview").retrieve().toBodilessEntity();
        return cookieManager.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing XSRF-TOKEN cookie"));
    }

    @Test
    void overviewEndpointReturnsActivationMetadata() {
        var response = getMap("/bootui/api/overview");

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
    void bootUiUnsafeRequestsRequireCsrfToken() {
        ResponseEntity<String> response = client().post()
                .uri("/bootui/api/loggers/io.github.jdubois.bootui.sample")
                .body(Map.of("level", "INFO"))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void configEndpointListsPropertiesAndMasksSecrets() {
        postMap("/bootui/api/config/overrides", Map.of("name", "demo.api.token", "value", "topsecret"));

        var response = getMap("/bootui/api/config");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Iterable<?>) body.get("sources")).anyMatch(s -> "bootui-overrides".equals(s));

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
        var response = getMap("/bootui/api/health");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isIn("UP", "DOWN", "UNKNOWN", "OUT_OF_SERVICE", "DISABLED");
    }

    @Test
    void loggersEndpointExposesKnownLoggers() {
        var response = getMap("/bootui/api/loggers");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Iterable<?>) body.get("availableLevels"))
                .anyMatch("INFO"::equals)
                .anyMatch("DEBUG"::equals);
        assertThat((Iterable<?>) body.get("loggers")).isNotEmpty();
    }

    @Test
    void postLoggerLevelChangesEffectiveLevel() {
        var response = postMap("/bootui/api/loggers/io.github.jdubois.bootui.sample", Map.of("level", "WARN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("configuredLevel")).isEqualTo("WARN");
        assertThat(body.get("effectiveLevel")).isEqualTo("WARN");
    }

    @Test
    void invalidLoggerLevelReturnsBadRequest() {
        var response = postMap("/bootui/api/loggers/io.github.jdubois.bootui.sample", Map.of("level", "NOT-A-LEVEL"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void configOverrideRoundtripPersistsAndDeletes() throws Exception {
        var put = postMap("/bootui/api/config/overrides", Map.of("name", "sample.greeting", "value", "Hola"));

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> putBody = put.getBody();
        assertThat(putBody).isNotNull();
        assertThat(putBody.get("value")).isEqualTo("Hola");
        assertThat((String) putBody.get("message")).containsIgnoringCase("restart");

        assertThat(Files.exists(OVERRIDES_FILE)).isTrue();
        assertThat(Files.readString(OVERRIDES_FILE)).contains("sample.greeting=Hola");

        ResponseEntity<Map<String, Object>> delete = client().delete()
                .uri("/bootui/api/config/overrides/sample.greeting")
                .headers(headers -> applyCsrfToken("/bootui/api/config/overrides/sample.greeting", headers))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> deleteBody = delete.getBody();
        assertThat(deleteBody).isNotNull();
        assertThat(deleteBody.get("previousValue")).isEqualTo("Hola");
        assertThat(Files.readString(OVERRIDES_FILE)).doesNotContain("sample.greeting=Hola");
    }

    @Test
    void beansEndpointReturnsBeanList() {
        var response = getMap("/bootui/api/beans");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Integer) body.get("total")).isGreaterThan(0);
    }

    @Test
    void mappingsEndpointReturnsContexts() {
        var response = getMap("/bootui/api/mappings");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        // Mappings controller returns the raw ApplicationMappingsDescriptor.
        assertThat(body.containsKey("contexts")).isTrue();
    }

    @Test
    void conditionsEndpointReturnsStableDto() {
        var response = getMap("/bootui/api/conditions");

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
        var response = getMap("/bootui/api/startup");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("steps")).isInstanceOf(List.class);
    }

    @Test
    void scheduledEndpointFindsSampleTask() {
        var response = getMap("/bootui/api/scheduled");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("schedulingPresent")).isEqualTo(true);
        assertThat(((Number) body.get("total")).intValue()).isGreaterThan(0);
        assertThat((Iterable<?>) body.get("tasks")).anySatisfy(task -> {
            Map<?, ?> dto = (Map<?, ?>) task;
            assertThat(dto.get("runnable")).asString().contains("EchoScheduler");
            assertThat(dto.get("triggerType")).isIn("FIXED_RATE", "FIXED_DELAY", "CRON");
        });
    }

    @Test
    void memoryEndpointReturnsJvmMemoryReport() {
        var response = getMap("/bootui/api/live-memory");

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
        assertThat(calculation.get("jvmOptions"))
                .asString()
                .contains("-Xmx")
                .contains("-XX:MaxMetaspaceSize=")
                .contains("-XX:ReservedCodeCacheSize=");
    }

    @Test
    void profilesEndpointReturnsActiveProfileReport() {
        var response = getMap("/bootui/api/profile-diff");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(((List<?>) body.get("activeProfiles")).contains("dev")).isTrue();
        assertThat(body.get("profileSources")).isInstanceOf(List.class);
    }

    @Test
    void httpProbeEndpointCallsLoopbackSampleEndpoint() {
        var response = postMap(
                "/bootui/api/http-probe",
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
        var response = getList("/bootui/api/log-tail/recent");

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

        ResponseEntity<String> adminWithDeveloperCredentials =
                getStringWithBasicAuth("/admin", "developer", "developer");
        assertThat(adminWithDeveloperCredentials.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> adminWithAdminCredentials = getStringWithBasicAuth("/admin", "admin", "admin");
        assertThat(adminWithAdminCredentials.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminWithAdminCredentials.getBody()).isEqualTo("BootUI sample admin");
    }

    @Test
    void sampleChatEndpointReportsUnavailableWhenSpringAiClientIsDisabled() {
        var response = postMap("/api/chat", Map.of("message", "What can BootUI show me?"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).asString().contains("Spring AI ChatClient");
    }

    @Test
    void sampleChatEndpointRejectsBlankMessages() {
        var response = postMap("/api/chat", Map.of("message", " "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("Message must not be blank.");
    }

    @Test
    void sampleProductsEndpointReturnsSqlInitializedProducts() {
        var response = getList("/api/sample/products");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        List<String> names = response.getBody().stream()
                .map(product -> String.valueOf(((Map<?, ?>) product).get("name")))
                .toList();
        assertThat(names).contains("BootUI Starter", "Sample Console").doesNotContain("Archived Prototype");
    }

    @Test
    void hibernateSecondLevelCacheSampleProducesMissPutAndHit() {
        var response = getMap("/api/sample/hibernate-second-level-cache");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("loads")).isEqualTo(2);
        assertThat(((Number) body.get("missCountDelta")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) body.get("putCountDelta")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) body.get("hitCountDelta")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(body.get("regionName")).isEqualTo(Product.class.getName());
        assertThat(((Map<?, ?>) body.get("product")).get("name")).isNotNull();
    }

    @Test
    void rootIndexPageIntroducesTheSampleApp() {
        ResponseEntity<String> response = getString("/");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("Welcome to the BootUI sample app")
                .contains("Open BootUI")
                .contains("href=\"/bootui/\"")
                .contains("Ask Spring AI")
                .contains("id=\"ai-chat-form\"")
                .contains("POST /api/chat")
                .contains("GET /api/sample/products");
    }

    @Test
    void secureApiEndpointRequiresAdminRole() {
        ResponseEntity<String> secureWithoutCredentials = getString("/api/secure");
        assertThat(secureWithoutCredentials.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> secureWithDeveloperCredentials =
                getStringWithBasicAuth("/api/secure", "developer", "developer");
        assertThat(secureWithDeveloperCredentials.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> secureWithAdminCredentials = getStringWithBasicAuth("/api/secure", "admin", "admin");
        assertThat(secureWithAdminCredentials.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secureWithAdminCredentials.getBody()).isEqualTo("Secure Hello, world");
    }

    @Test
    void securedSqlEndpointRequiresAdminRoleAndReturnsProducts() {
        ResponseEntity<String> withoutCredentials = getString("/api/secure/products");
        assertThat(withoutCredentials.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> asDeveloper = getStringWithBasicAuth("/api/secure/products", "developer", "developer");
        assertThat(asDeveloper.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> asAdmin = getStringWithBasicAuth("/api/secure/products", "admin", "admin");
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asAdmin.getBody()).startsWith("[").contains("\"name\"").contains("\"category\"");
    }

    @Test
    void dataEndpointFindsSampleJpaRepository() {
        var response = getMap("/bootui/api/data/repositories");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("springDataPresent")).isEqualTo(true);
        assertThat(((Number) body.get("total")).intValue()).isGreaterThan(0);
        assertThat((Iterable<?>) body.get("repositories")).anySatisfy(repository -> {
            Map<?, ?> dto = (Map<?, ?>) repository;
            assertThat(dto.get("repositoryInterface")).isEqualTo(ProductRepository.class.getName());
            assertThat(dto.get("domainType")).isEqualTo(Product.class.getName());
            assertThat(dto.get("storeModule")).isEqualTo("JPA");
            assertThat(((Number) dto.get("queryMethodCount")).intValue()).isGreaterThan(0);
        });
    }

    @Test
    void dataRepositoryDetailIncludesAnnotatedQueryMethod() {
        var response = getMap("/bootui/api/data/repositories/" + ProductRepository.class.getName());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Iterable<?>) body.get("methods")).anySatisfy(method -> {
            Map<?, ?> dto = (Map<?, ?>) method;
            assertThat(dto.get("name")).isEqualTo("searchByName");
            assertThat(dto.get("origin")).isEqualTo("ANNOTATED");
            assertThat((String) dto.get("query")).contains("select p from Product p");
        });
    }

    @Test
    void productSearchProducesACapturedTransactionBoundary() {
        Map<?, ?> before = getMap("/bootui/api/transactions").getBody();
        assertThat(before).isNotNull();
        long capturedBefore = ((Number) before.get("totalCaptured")).longValue();

        ResponseEntity<List<Object>> search = client().get()
                .uri("/api/sample/product-search?term=console")
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> after = getMap("/bootui/api/transactions").getBody();
        assertThat(after).isNotNull();
        assertThat(((Number) after.get("totalCaptured")).longValue()).isGreaterThan(capturedBefore);
        assertThat((Iterable<?>) after.get("entries"))
                .anySatisfy(entry -> assertThat(((Map<?, ?>) entry).get("methodName"))
                        .isEqualTo("io.github.jdubois.bootui.sample.catalog.SampleCatalog.searchProducts"));
    }

    @Test
    void productSearchSqlIsAttributedToTheSampleCallSite() {
        ResponseEntity<List<Object>> search = client().get()
                .uri("/api/sample/product-search?term=console")
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);

        var response = getMap("/bootui/api/sql-trace");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        // The sample app lives under io.github.jdubois.bootui.sample, which is application code rather than one
        // of BootUI's own module packages, so the statement names the sample method that issued it.
        assertThat((Iterable<?>) body.get("entries")).anySatisfy(entry -> {
            Map<?, ?> dto = (Map<?, ?>) entry;
            assertThat((String) dto.get("sql")).containsIgnoringCase("sample_products");
            assertThat((String) dto.get("callSite"))
                    .startsWith("io.github.jdubois.bootui.sample.catalog.SampleCatalog.searchProducts(");
        });
    }

    @Test
    void transactionSamplesProduceRepresentativeBoundaries() {
        Map<?, ?> before = getMap("/bootui/api/transactions").getBody();
        assertThat(before).isNotNull();
        long capturedBefore = ((Number) before.get("totalCaptured")).longValue();

        var sampleResponse = getMap("/api/sample/transaction-samples");
        assertThat(sampleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sampleResponse.getBody()).containsEntry("slowMillis", 650);

        Map<?, ?> after = getMap("/bootui/api/transactions").getBody();
        assertThat(after).isNotNull();
        assertThat(((Number) after.get("totalCaptured")).longValue()).isGreaterThanOrEqualTo(capturedBefore + 5);
        Iterable<?> entries = (Iterable<?>) after.get("entries");
        assertThat(entries).anySatisfy(entry -> {
            Map<?, ?> transaction = (Map<?, ?>) entry;
            assertThat(transaction.get("methodName"))
                    .isEqualTo("io.github.jdubois.bootui.sample.catalog.SampleTransactionScenarios.commit");
            assertThat(transaction.get("status")).isEqualTo("COMMITTED");
        });
        assertThat(entries).anySatisfy(entry -> {
            Map<?, ?> transaction = (Map<?, ?>) entry;
            assertThat(transaction.get("methodName"))
                    .isEqualTo("io.github.jdubois.bootui.sample.catalog.SampleTransactionScenarios.slowCommit");
            assertThat(transaction.get("status")).isEqualTo("COMMITTED");
            assertThat(transaction.get("slow")).isEqualTo(true);
        });
        assertThat(entries).anySatisfy(entry -> {
            Map<?, ?> transaction = (Map<?, ?>) entry;
            assertThat(transaction.get("methodName"))
                    .isEqualTo("io.github.jdubois.bootui.sample.catalog.SampleTransactionScenarios.rollBack");
            assertThat(transaction.get("status")).isEqualTo("ROLLED_BACK");
        });
        assertThat(entries).anySatisfy(entry -> {
            Map<?, ?> transaction = (Map<?, ?>) entry;
            assertThat(transaction.get("methodName"))
                    .isEqualTo("io.github.jdubois.bootui.sample.catalog.SampleNestedTransactionStep.run");
            assertThat(transaction.get("parentId")).isNotNull();
        });
    }

    @Test
    void flywayEndpointListsAppliedAndPendingCatalogMigrations() {
        var response = getMap("/bootui/api/flyway/migrations");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("flywayPresent")).isEqualTo(true);
        assertThat(((Number) body.get("total")).intValue()).isGreaterThanOrEqualTo(4);
        assertThat((Iterable<?>) body.get("databases")).anySatisfy(database -> {
            Map<?, ?> dto = (Map<?, ?>) database;
            assertThat(dto.get("currentVersion")).isEqualTo("2");
            assertThat(((Number) dto.get("applied")).intValue()).isGreaterThanOrEqualTo(2);
            assertThat(((Number) dto.get("pending")).intValue()).isGreaterThanOrEqualTo(2);
            assertThat(dto.get("cleanEnabled")).isEqualTo(false);
            assertThat((Iterable<?>) dto.get("migrations")).anySatisfy(migration -> {
                Map<?, ?> entry = (Map<?, ?>) migration;
                assertThat(entry.get("version")).isEqualTo("3");
                assertThat(entry.get("state")).isEqualTo("Pending");
                assertThat((String) entry.get("description")).contains("add catalog tags");
            });
        });
    }

    @Test
    void liquibaseEndpointListsAppliedAndPendingInventoryChangeSets() {
        var response = getMap("/bootui/api/liquibase/changesets");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("liquibasePresent")).isEqualTo(true);
        assertThat(((Number) body.get("total")).intValue()).isEqualTo(4);
        assertThat((Iterable<?>) body.get("databases")).anySatisfy(database -> {
            Map<?, ?> dto = (Map<?, ?>) database;
            assertThat(((Number) dto.get("applied")).intValue()).isEqualTo(2);
            assertThat(((Number) dto.get("pending")).intValue()).isEqualTo(2);
            assertThat(dto.get("updateEnabled")).isEqualTo(true);
            assertThat((Iterable<?>) dto.get("changeSets")).anySatisfy(changeSet -> {
                Map<?, ?> entry = (Map<?, ?>) changeSet;
                assertThat(entry.get("author")).isEqualTo("bootui");
                assertThat(entry.get("execType")).isEqualTo("EXECUTED");
            });
            assertThat((Iterable<?>) dto.get("changeSets")).anySatisfy(changeSet -> {
                Map<?, ?> entry = (Map<?, ?>) changeSet;
                assertThat(entry.get("id")).isEqualTo("3");
                assertThat(entry.get("execType")).isEqualTo("PENDING");
            });
        });
    }

    @Test
    void cacheEndpointFindsSampleCachesAndClearsOneCache() {
        getList("/api/sample/products");

        var response = getMap("/bootui/api/cache");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("cacheAvailable")).isEqualTo(true);
        assertThat(body.get("clearEnabled")).isEqualTo(true);
        assertThat(body.get("truncated")).isEqualTo(false);
        assertThat((Integer) body.get("tierCount")).isPositive();
        assertThat((Iterable<?>) body.get("managers")).anySatisfy(manager -> {
            Map<?, ?> dto = (Map<?, ?>) manager;
            assertThat(dto.get("type")).asString().contains("ConcurrentMapCacheManager");
            assertThat(dto.get("composition")).isEqualTo("SIMPLE");
            assertThat(dto.get("dynamicCaches")).isEqualTo("UNKNOWN");
            assertThat((Iterable<?>) dto.get("caches")).anySatisfy(entry -> {
                Map<?, ?> cache = (Map<?, ?>) entry;
                assertThat(cache.get("name")).isEqualTo("sample-products");
                assertThat(cache.get("opaque")).isEqualTo(false);
                // This test pins spring.cache.type=simple, so the cache is one in-memory map tier whose
                // statistics are honestly unavailable rather than a series of zeroes.
                assertThat((Iterable<?>) cache.get("tiers")).singleElement().satisfies(tierEntry -> {
                    Map<?, ?> tier = (Map<?, ?>) tierEntry;
                    assertThat(tier.get("locality")).isEqualTo("LOCAL");
                    assertThat(tier.get("level")).isEqualTo(0);
                    Map<?, ?> tierStatistics = (Map<?, ?>) tier.get("statistics");
                    assertThat(tierStatistics.get("available")).isEqualTo(false);
                    assertThat(tierStatistics.get("scope")).isEqualTo("TIER");
                    assertThat(tierStatistics.get("hits")).isNull();
                    assertThat(tierStatistics.get("unavailableReason"))
                            .asString()
                            .isNotEmpty();
                });
                Map<?, ?> statistics = (Map<?, ?>) cache.get("statistics");
                assertThat(statistics.get("available")).isEqualTo(false);
                assertThat(statistics.get("scope")).isEqualTo("CACHE");
                assertThat(statistics.get("hitRatio")).isNull();
            });
        });
        assertThat((Iterable<?>) body.get("operations")).anySatisfy(operation -> {
            Map<?, ?> dto = (Map<?, ?>) operation;
            assertThat(dto.get("operation")).isEqualTo("@Cacheable");
            assertThat((Iterable<?>) dto.get("caches")).anyMatch("sample-products"::equals);
        });

        var clear = postMap(
                "/bootui/api/cache/clear",
                Map.of("managerName", "cacheManager", "cacheName", "sample-products", "confirm", true));
        assertThat(clear.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(clear.getBody()).isNotNull();
        assertThat(clear.getBody().get("status")).isEqualTo("cleared");
    }

    @Test
    void springSecurityEndpointFindsSampleFilterChains() {
        var response = getMap("/bootui/api/spring-security");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("springSecurityPresent")).isEqualTo(true);
        assertThat((Iterable<?>) body.get("chains")).anySatisfy(chain -> {
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
    void springSecurityExplainMatchesSecureApiRequest() {
        var response = getMap("/bootui/api/spring-security/explain?method=GET&path=/api/secure");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("matched")).isEqualTo(true);
        assertThat(body.get("matcherDescription")).asString().contains("/api/secure");
        assertThat((Iterable<?>) body.get("filters"))
                .anySatisfy(filter -> assertThat(filter).isEqualTo("BasicAuthenticationFilter"));
    }

    @Test
    void springSecurityEndpointsListsControllerMappingsWithRules() {
        var response = getMap("/bootui/api/spring-security/endpoints");

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
            if (!"/bootui/api/spring-security".equals(dto.get("pattern"))) {
                return;
            }
            assertThat(dto.get("secured")).isEqualTo(true);
            assertThat(dto.get("rule")).isEqualTo("permitAll");
            assertThat(dto.get("chainIndex")).isEqualTo(0);
        });
    }

    @Test
    void securityLogsEndpointListsMaskedAuditEvents() {
        var response = getMap("/bootui/api/security-logs");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("auditEventsPresent")).isEqualTo(true);
        assertThat(body.get("maxLogs")).isEqualTo(500);
        Iterable<?> events = (Iterable<?>) body.get("events");
        assertThat(events).anySatisfy(item -> {
            Map<?, ?> dto = (Map<?, ?>) item;
            assertThat(dto.get("type")).isEqualTo("AUTHORIZATION_DENIED");
            assertThat((Iterable<?>) dto.get("data")).anySatisfy(data -> {
                Map<?, ?> dataDto = (Map<?, ?>) data;
                if (!"sessionId".equals(dataDto.get("name"))) {
                    return;
                }
                assertThat(dataDto.get("value")).isEqualTo("******");
                assertThat(dataDto.get("masked")).isEqualTo(true);
            });
        });
    }

    @Test
    void bootUiSpaIndexIsServed() {
        ResponseEntity<String> response =
                client().get().uri("/bootui/").retrieve().toEntity(String.class);

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
        public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {}
    }
}
