package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * R2 optional-dependency absence smoke test.
 *
 * <p>The Hibernate advisor pulls {@code jakarta.persistence} (the engine's first optional dependency)
 * and gates its bean wiring behind {@code @ConditionalOnClass}. This test proves the gate fails closed:
 * with {@code jakarta.persistence.EntityManagerFactory} and {@code org.hibernate.SessionFactory} removed
 * from the classpath, BootUI still starts, neither the engine {@link HibernateScanner} bean nor the
 * {@link HibernateController} bean is defined, and no {@code NoClassDefFoundError} /
 * {@code ClassNotFoundException} leaks from the JPA-typed bean factory or controller.</p>
 */
class HibernateAdvisorAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void doesNotWireHibernateAdvisorWhenJpaAndHibernateAreAbsent() {
        runner.withClassLoader(new FilteredClassLoader(
                        "jakarta.persistence.EntityManagerFactory", "org.hibernate.SessionFactory"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HibernateScanner.class);
                    assertThat(context).doesNotHaveBean(HibernateController.class);
                });
    }
}
