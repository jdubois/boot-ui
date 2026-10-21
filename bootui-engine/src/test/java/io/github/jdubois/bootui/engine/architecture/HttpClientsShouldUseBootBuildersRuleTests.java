package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** ARCH-SPRING-025: HTTP clients built from static factories instead of Spring Boot's auto-configured builder. */
class HttpClientsShouldUseBootBuildersRuleTests {

    private static final HttpClientsShouldUseBootBuildersRule BOTH_MODULES =
            new HttpClientsShouldUseBootBuildersRule(name -> true);

    @Test
    void staticRestClientAndWebClientFactoriesAreReported() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                BOTH_MODULES,
                StaticRestClientFactories.class,
                StaticWebClientFactories.class);

        assertThat(result.id()).isEqualTo("ARCH-SPRING-025");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(4);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("orders").contains("RestClient.builder(..)"))
                .anySatisfy(sample -> assertThat(sample).contains("inventory").contains("RestClient.create(..)"))
                .anySatisfy(sample -> assertThat(sample).contains("events").contains("WebClient.create(..)"))
                .anySatisfy(sample -> assertThat(sample)
                        .as("method references are call sites too")
                        .contains("builderSupplier")
                        .contains("WebClient.builder(..)"))
                .allSatisfy(sample -> assertThat(sample).contains("auto-configured"));
    }

    @Test
    void clientsBuiltFromTheInjectedBuilderPass() {
        ArchitectureRuleResultDto result =
                evaluate(ArchitecturePlatform.SPRING, BOTH_MODULES, InjectedBuilderClients.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void eachClientFamilyIsCheckedOnlyWhenItsBootModuleIsPresent() {
        HttpClientsShouldUseBootBuildersRule restClientOnly = new HttpClientsShouldUseBootBuildersRule(
                HttpClientsShouldUseBootBuildersRule.REST_CLIENT_MODULE_MARKER::equals);

        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                restClientOnly,
                StaticRestClientFactories.class,
                StaticWebClientFactories.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .hasSize(2)
                .allSatisfy(sample -> assertThat(sample).contains("RestClient."));
    }

    @Test
    void ruleIsSkippedWithoutAnyBootClientModule() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new HttpClientsShouldUseBootBuildersRule(name -> false),
                StaticRestClientFactories.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.SKIPPED);
    }

    @Test
    void defaultProbeLooksForTheBootModulesOnTheClasspath() {
        // The engine's test classpath has spring-web and spring-webflux but neither Spring Boot client module.
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new HttpClientsShouldUseBootBuildersRule(),
                StaticRestClientFactories.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.SKIPPED);
    }

    @Test
    void ruleIsNotEvaluatedOnQuarkus() {
        ArchitectureRuleResultDto result =
                evaluate(ArchitecturePlatform.QUARKUS, BOTH_MODULES, StaticRestClientFactories.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.SKIPPED);
    }

    @Test
    void generatedClassesAreNotReported() {
        JavaClasses classes = new ClassFileImporter().importClasses(GeneratedApiClient.class);
        JavaClasses handwritten = new ArchitectureGeneratedCode.Result(
                        Set.of(GeneratedApiClient.class.getName()), List.of())
                .handwrittenClasses(classes);
        ArchitectureContext context = new ArchitectureContext(
                classes,
                List.of(getClass().getPackageName()),
                ArchitecturePlatform.SPRING,
                new ArchitectureContext.ArchitectureEvaluationEvidence(),
                new AdvisorViolationCollector(100),
                handwritten);

        assertThat(evaluate(ArchitecturePlatform.SPRING, BOTH_MODULES, GeneratedApiClient.class)
                        .status())
                .as("the same class is reported while it is not known to be generated")
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(BOTH_MODULES.evaluate(context).status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    private static ArchitectureRuleResultDto evaluate(
            ArchitecturePlatform platform, ArchitectureRule rule, Class<?>... classes) {
        return rule.evaluate(new ArchitectureContext(
                new ClassFileImporter().importClasses(classes),
                List.of(HttpClientsShouldUseBootBuildersRuleTests.class.getPackageName()),
                platform));
    }

    static class StaticRestClientFactories {

        RestClient orders() {
            return RestClient.builder().baseUrl("http://orders").build();
        }

        RestClient inventory() {
            return RestClient.create("http://inventory");
        }
    }

    static class StaticWebClientFactories {

        WebClient events() {
            return WebClient.create("http://events");
        }

        Supplier<WebClient.Builder> builderSupplier() {
            return WebClient::builder;
        }
    }

    static class InjectedBuilderClients {

        private final RestClient.Builder restClientBuilder;
        private final WebClient.Builder webClientBuilder;

        InjectedBuilderClients(RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder) {
            this.restClientBuilder = restClientBuilder;
            this.webClientBuilder = webClientBuilder;
        }

        RestClient orders() {
            return restClientBuilder.clone().baseUrl("http://orders").build();
        }

        RestClient ordersWithTimeouts(RestClient orders) {
            return orders.mutate().build();
        }

        WebClient events() {
            return webClientBuilder.clone().baseUrl("http://events").build();
        }
    }

    /** Shape of the OpenAPI Generator {@code restclient} ApiClient default constructor. */
    static class GeneratedApiClient {

        private final RestClient restClient;

        GeneratedApiClient() {
            this(RestClient.builder().build());
        }

        GeneratedApiClient(RestClient restClient) {
            this.restClient = restClient;
        }

        RestClient restClient() {
            return restClient;
        }
    }
}
