package io.github.jdubois.bootui.autoconfigure.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;

/**
 * Pins the Spring {@link SpringBasePackageProvider}: it must surface the host application's
 * {@code @SpringBootApplication} base packages (as recorded by {@code AutoConfigurationPackages}) and
 * degrade to an empty list — never throw — when no base packages are registered. This is the seam the
 * ArchUnit-based advisors rely on to bound their import to the application's own code.
 */
class SpringBasePackageProviderTests {

    @Test
    void returnsRegisteredAutoConfigurationPackages() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AutoConfigurationPackages.register(beanFactory, "com.example.app", "com.example.support");

        SpringBasePackageProvider provider = new SpringBasePackageProvider(beanFactory);

        assertThat(provider.basePackages()).containsExactly("com.example.app", "com.example.support");
    }

    @Test
    void returnsEmptyListWhenNoBasePackagesRegistered() {
        SpringBasePackageProvider provider = new SpringBasePackageProvider(new DefaultListableBeanFactory());

        assertThat(provider.basePackages()).isEmpty();
    }

    @Test
    void returnsEmptyListWhenBeanFactoryIsNull() {
        SpringBasePackageProvider provider = new SpringBasePackageProvider(null);

        assertThat(provider.basePackages()).isEmpty();
    }
}
