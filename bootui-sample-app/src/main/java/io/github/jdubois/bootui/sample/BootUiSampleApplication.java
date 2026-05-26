package io.github.jdubois.bootui.sample;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BootUiSampleApplication.SampleSettings.class)
public class BootUiSampleApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BootUiSampleApplication.class);
        composeFileDefault().ifPresent(composeFile -> application.setDefaultProperties(
                Map.of("spring.docker.compose.file", composeFile.toString())));
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
        private final ProductRepository products;

        public SampleController(SampleSettings settings, ProductRepository products) {
            this.settings = settings;
            this.products = products;
        }

        @GetMapping("/hello")
        public String hello() {
            return settings.getGreeting() + ", BootUI! (retries=" + settings.getRetries() + ")";
        }

        @GetMapping("/products")
        public List<ProductSummary> products() {
            return products.findByActiveTrueOrderByNameAsc().stream()
                    .map(ProductSummary::from)
                    .toList();
        }
    }

    public record ProductSummary(Long id, String name, String category, boolean active) {

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
