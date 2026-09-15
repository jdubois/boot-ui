package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiReactiveAutoConfiguration;
import io.github.jdubois.bootui.engine.mysql.MySqlInsightService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class MySqlAbsenceTest {

    @Test
    void servletStartsWithoutADriverOrDatasource() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("com.mysql", "org.mariadb"))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MySqlInsightService.class);
                    MySqlController controller = context.getBean(MySqlController.class);
                    assertThat(controller.report().status()).isEqualTo("NOT_READ");
                    assertThat(controller.read().status()).isEqualTo("DISABLED");
                });
    }

    @Test
    void webFluxStartsWithoutADriverOrDatasource() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiReactiveAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("com.mysql", "org.mariadb"))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MySqlInsightService.class);
                    assertThat(context.getBean(MySqlController.class).report().status())
                            .isEqualTo("NOT_READ");
                });
    }
}
