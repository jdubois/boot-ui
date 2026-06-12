package io.github.jdubois.bootui.sample;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties(BootUiSampleApplication.SampleSettings.class)
public class BootUiSampleApplication {

    private static final Logger log = LoggerFactory.getLogger(BootUiSampleApplication.class);

    private static final String FLYWAY_STARTUP_TARGET = "2";
    private static final String LIQUIBASE_BASE_CHANGELOG = "classpath:db/changelog/db.changelog-base.xml";
    private static final String LIQUIBASE_MASTER_CHANGELOG = "classpath:db/changelog/db.changelog-master.xml";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BootUiSampleApplication.class);
        composeFileDefault()
                .ifPresent(composeFile ->
                        application.setDefaultProperties(Map.of("spring.docker.compose.file", composeFile.toString())));
        application.run(args);
    }

    static Optional<Path> composeFileDefault() {
        return composeFileDefault(Path.of("").toAbsolutePath());
    }

    static Optional<Path> composeFileDefault(Path workingDirectory) {
        if (Files.isRegularFile(workingDirectory.resolve("compose.yaml"))) {
            return Optional.empty();
        }
        Path moduleComposeFile = workingDirectory.resolve(Path.of("bootui-sample-app", "compose.yaml"));
        return Files.isRegularFile(moduleComposeFile) ? Optional.of(moduleComposeFile) : Optional.empty();
    }

    @EventListener(ApplicationReadyEvent.class)
    void logStartupTime(ApplicationReadyEvent event) {
        log.info("Sample app started up in {} ms", event.getTimeTaken().toMillis());
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
    FlywayMigrationStrategy sampleFlywayStartupStrategy() {
        return flyway -> {
            // BootUI should demonstrate pending migrations, so the sample applies
            // its base schema in a runner below.
        };
    }

    @Bean
    ApplicationRunner sampleMigrationDemoInitializer(
            ObjectProvider<Flyway> flywayProvider,
            ObjectProvider<SpringLiquibase> liquibaseProvider,
            DataSource dataSource,
            ResourceLoader resourceLoader) {
        return args -> {
            // The sample migrations are disabled by default in the Docker images for a faster
            // startup (and can be toggled with SPRING_FLYWAY_ENABLED / SPRING_LIQUIBASE_ENABLED),
            // so this demo wiring must tolerate either tool being absent rather than fail to start.
            // Gating on bean presence (rather than reading the property at runtime) keeps the
            // behaviour consistent for the native image, where the toggle is baked in at build time.
            Flyway flyway = flywayProvider.getIfAvailable();
            if (flyway != null) {
                Flyway.configure()
                        .configuration(flyway.getConfiguration())
                        .target(FLYWAY_STARTUP_TARGET)
                        .load()
                        .migrate();
            }

            if (liquibaseProvider.getIfAvailable() != null) {
                SpringLiquibase baseLiquibase = new SpringLiquibase();
                baseLiquibase.setDataSource(dataSource);
                baseLiquibase.setResourceLoader(resourceLoader);
                baseLiquibase.setChangeLog(LIQUIBASE_BASE_CHANGELOG);
                baseLiquibase.setShouldRun(true);
                baseLiquibase.afterPropertiesSet();
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.liquibase", name = "enabled", matchIfMissing = true)
    SpringLiquibase liquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(LIQUIBASE_MASTER_CHANGELOG);
        liquibase.setShouldRun(false);
        return liquibase;
    }

    @ConfigurationProperties(prefix = "sample")
    public static class SampleSettings {

        private String greeting = "Hello";

        private int retries = 3;

        public String getGreeting() {
            return greeting;
        }

        public void setGreeting(String greeting) {
            this.greeting = greeting;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }
    }

    @RestController
    @RequestMapping("/api/sample")
    public static class SampleController {

        private final SampleSettings settings;
        private final SampleCatalog catalog;

        public SampleController(SampleSettings settings, SampleCatalog catalog) {
            this.settings = settings;
            this.catalog = catalog;
        }

        @GetMapping("/hello")
        public String hello() {
            return catalog.greeting(settings.getGreeting(), settings.getRetries());
        }

        @GetMapping("/products")
        public List<ProductSummary> products() {
            return catalog.activeProducts();
        }

        @GetMapping("/product-search")
        public List<ProductSummary> productSearch(@RequestParam(name = "term", defaultValue = "console") String term) {
            return catalog.searchProducts(term);
        }

        @GetMapping("/session")
        public Map<String, Object> session(HttpServletRequest request) {
            HttpSession session = request.getSession(true);
            Object previousClickCount = session.getAttribute("sampleClickCount");
            int sampleClickCount = previousClickCount instanceof Number number ? number.intValue() + 1 : 1;
            session.setAttribute("sampleMessage", "Hello from the sample session");
            session.setAttribute("sampleCount", 42);
            session.setAttribute("sampleClickCount", sampleClickCount);
            session.setAttribute("sampleGeneratedAt", Instant.now().toString());
            session.setAttribute("apiToken", "sample-secret-token");
            return Map.of(
                    "sessionId",
                    session.getId(),
                    "attributeCount",
                    5,
                    "sampleClickCount",
                    sampleClickCount,
                    "attributes",
                    List.of("sampleMessage", "sampleCount", "sampleClickCount", "sampleGeneratedAt", "apiToken"));
        }
    }

    @Service
    public static class SampleCatalog {

        private final ProductRepository products;

        public SampleCatalog(ProductRepository products) {
            this.products = products;
        }

        @Cacheable(cacheNames = "sample-greetings", key = "#greeting + ':' + #retries")
        public String greeting(String greeting, int retries) {
            return greeting + ", BootUI! (retries=" + retries + ")";
        }

        @Cacheable(cacheNames = "sample-products", key = "'active'", unless = "#result.isEmpty()")
        public List<ProductSummary> activeProducts() {
            return products.findByActiveTrueOrderByNameAsc().stream()
                    .map(ProductSummary::from)
                    .toList();
        }

        public List<ProductSummary> searchProducts(String term) {
            // Intentionally uncached so every call runs a live SQL SELECT for the SQL Trace panel to capture.
            return products.searchByName(term).stream()
                    .map(ProductSummary::from)
                    .toList();
        }

        @CacheEvict(cacheNames = "sample-products", allEntries = true)
        public void evictProducts() {}
    }

    public record ProductSummary(Long id, String name, String category, boolean active) implements Serializable {

        static ProductSummary from(Product product) {
            return new ProductSummary(product.getId(), product.getName(), product.getCategory(), product.isActive());
        }
    }

    @RestController
    @RequestMapping("/api")
    public static class HelloController {

        @GetMapping("/hello")
        public String hello() {
            return "Hello, world";
        }

        @GetMapping("/secure")
        public String secure() {
            return "Secure Hello, world";
        }
    }

    @RestController
    @RequestMapping("/admin")
    public static class AdminController {

        @GetMapping
        public String admin() {
            return "BootUI sample admin";
        }
    }
}
