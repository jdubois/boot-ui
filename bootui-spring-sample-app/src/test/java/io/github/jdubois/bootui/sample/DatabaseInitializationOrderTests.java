package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The sample starts whatever order its libraries are on the class path in. Flyway, Liquibase, and JPA register their
 * {@code DatabaseInitializerDetector}s at the same order, so {@code META-INF/spring.factories} order breaks the tie, and
 * that order is the class path's: Maven 3.10 lists the repackaged jar's {@code BOOT-INF/lib} differently than Maven 3.9
 * did, which once made {@code java -jar} fail on a circular depends-on between {@code flyway} and
 * {@code entityManagerFactory}. Each run here puts one library's {@code spring.factories} first.
 */
class DatabaseInitializationOrderTests {

    @ParameterizedTest
    @ValueSource(strings = {"spring-boot-flyway-", "spring-boot-jpa-", "spring-boot-liquibase-"})
    void startsWhicheverDetectorLoadsFirst(String library) {
        String name = library.replace("spring-boot-", "").replace("-", "");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BootUiSampleApplication.class)
                .resourceLoader(new DefaultResourceLoader(new FactoriesFirst(library)))
                .run(
                        "--server.port=0",
                        "--spring.profiles.active=dev",
                        "--spring.datasource.url=jdbc:h2:mem:bootui_init_order_" + name + ";DB_CLOSE_DELAY=-1",
                        "--bootui.show-banner=false",
                        "--bootui.overrides-file=target/init-order-" + name + "/overrides.properties",
                        "--management.tracing.export.enabled=false",
                        "--spring.jmx.enabled=false")) {
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            // Hibernate creates the schema first; Flyway then baselines it, as
            // spring.jpa.defer-datasource-initialization
            // declares, and the entity manager factory never waits for Flyway in return.
            assertThat(beanFactory.getBeanDefinition("flyway").getDependsOn()).contains("entityManagerFactory");
            assertThat(dependsOn(beanFactory, "entityManagerFactory")).doesNotContain("flyway", "liquibase");
        }
    }

    private static List<String> dependsOn(ConfigurableListableBeanFactory beanFactory, String bean) {
        String[] dependsOn = beanFactory.getBeanDefinition(bean).getDependsOn();
        return dependsOn == null ? List.of() : List.of(dependsOn);
    }

    /** Lists the {@code spring.factories} of the library whose jar name starts with this prefix first. */
    private static final class FactoriesFirst extends ClassLoader {

        private final String library;

        FactoriesFirst(String library) {
            super(DatabaseInitializationOrderTests.class.getClassLoader());
            this.library = library;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            List<URL> resources = Collections.list(super.getResources(name));
            if ("META-INF/spring.factories".equals(name)) {
                resources.sort(Comparator.comparing(url -> !url.toString().contains("/" + library)));
            }
            return Collections.enumeration(resources);
        }
    }
}
