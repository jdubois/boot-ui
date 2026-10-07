package io.github.jdubois.bootui.sample.config;

import java.util.Arrays;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.sql.init.dependency.DatabaseInitializerDetector;
import org.springframework.core.Ordered;

/**
 * Makes the sample's database initialization order independent of the class path order.
 *
 * <p>The sample lets Hibernate create the schema ({@code ddl-auto=create}) and then Flyway and Liquibase baseline it,
 * which is what {@code spring.jpa.defer-datasource-initialization=true} declares: Spring Boot's
 * {@code JpaDatabaseInitializerDetector} then makes every other database initializer depend on the entity manager
 * factory. Spring Boot also chains initializers in the order of their detectors, each one depending on those detected
 * before, and the Flyway, Liquibase, and JPA detectors share the same order, so {@code META-INF/spring.factories} order,
 * which is the class path's, breaks the tie. When Flyway's or Liquibase's detector comes first (as in the repackaged jar
 * built by Maven 3.10, whose {@code BOOT-INF/lib} order differs from Maven 3.9's), the entity manager factory also
 * depends on that initializer, and the context fails with a circular depends-on relationship.
 *
 * <p>Running after Spring Boot's own post-processor, this drops only that reverse edge: an initializer that the JPA
 * initializer depends on, and that itself depends on the JPA initializer. The dependency the deferred initialization
 * declares wins whatever the detector order, rather than the order being pinned to one that happens to work.
 */
final class DeferredJpaInitializationOrder implements BeanFactoryPostProcessor, Ordered {

    private static final String DETECTOR = DatabaseInitializerDetector.class.getName();
    // Package-private in Spring Boot, so named rather than referenced.
    private static final String JPA_DETECTOR = "org.springframework.boot.jpa.JpaDatabaseInitializerDetector";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition jpa = beanFactory.getBeanDefinition(name);
            String[] dependsOn = jpa.getDependsOn();
            if (dependsOn != null && JPA_DETECTOR.equals(jpa.getAttribute(DETECTOR))) {
                jpa.setDependsOn(Arrays.stream(dependsOn)
                        .filter(initializer -> !dependsOn(beanFactory, initializer, name))
                        .toArray(String[]::new));
            }
        }
    }

    private static boolean dependsOn(ConfigurableListableBeanFactory beanFactory, String bean, String dependency) {
        if (!beanFactory.containsBeanDefinition(bean)) {
            return false;
        }
        String[] dependsOn = beanFactory.getBeanDefinition(bean).getDependsOn();
        return dependsOn != null && Arrays.asList(dependsOn).contains(dependency);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
