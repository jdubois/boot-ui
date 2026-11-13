package io.github.jdubois.bootui.autoconfigure.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

class BootUiCompatibilityWarningsTests {

    @Test
    void warnsForHybridJdbcAndReactiveApplicationsWithoutInstantiatingEitherClient() {
        var beans = new DefaultListableBeanFactory();
        beans.registerBeanDefinition("reactive", new RootBeanDefinition(ReactiveClient.class, () -> {
            throw new AssertionError("Startup diagnostics must not instantiate the reactive client");
        }));
        beans.registerBeanDefinition("jdbc", new RootBeanDefinition(DataSource.class, () -> {
            throw new AssertionError("Startup diagnostics must not instantiate the JDBC datasource");
        }));
        assertThat(BootUiCompatibilityWarnings.collect(
                        beans, getClass().getClassLoader(), ReactiveClient.class.getName()))
                .anySatisfy(warning -> assertThat(warning)
                        .contains("BOOTUI-COMPAT-041")
                        .contains("even when both clients are configured"));
        assertThat(beans.getSingletonCount()).isZero();
    }

    @Test
    void anUnusedOrAbsentReactiveLibraryDoesNotCreateACoverageWarning() {
        var beans = new DefaultListableBeanFactory();
        assertThat(BootUiCompatibilityWarnings.collect(
                        beans, getClass().getClassLoader(), ReactiveClient.class.getName()))
                .noneMatch(warning -> warning.contains("BOOTUI-COMPAT-041"));
        assertThat(BootUiCompatibilityWarnings.collect(beans, getClass().getClassLoader(), "missing.ReactiveClient"))
                .noneMatch(warning -> warning.contains("BOOTUI-COMPAT-04"));
    }

    static class ReactiveClient {}
}
