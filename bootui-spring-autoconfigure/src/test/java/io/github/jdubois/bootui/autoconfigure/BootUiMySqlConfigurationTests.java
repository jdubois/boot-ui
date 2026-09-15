package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.engine.mysql.MySqlRowLimits;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class BootUiMySqlConfigurationTests {

    private static final List<String> NAMES = List.of(
            "max-sessions",
            "max-statements",
            "max-indexes",
            "max-tables",
            "max-lock-waits",
            "max-replication-channels",
            "max-settings");

    @Test
    void defaultsAndEveryIndependentLimitBind() {
        assertThat(limits(new BootUiProperties())).isEqualTo(MySqlRowLimits.defaults());
        MockEnvironment environment = new MockEnvironment();
        for (int index = 0; index < NAMES.size(); index++) {
            environment.setProperty("bootui.mysql." + NAMES.get(index), Integer.toString(index + 11));
        }
        BootUiProperties properties =
                Binder.get(environment).bind("bootui", BootUiProperties.class).get();
        assertThat(limits(properties)).isEqualTo(new MySqlRowLimits(11, 12, 13, 14, 15, 16, 17));
    }

    @Test
    void invalidLimitsFailWithTheirPropertyName() {
        for (String name : NAMES) {
            for (String value : List.of("0", "-1", "2147483647", "2147483648", "", "1.5", "invalid")) {
                String key = "bootui.mysql." + name;
                assertThatThrownBy(() -> Binder.get(new MockEnvironment().withProperty(key, value))
                                .bind("bootui", BootUiProperties.class)
                                .get())
                        .hasStackTraceContaining(key);
            }
        }
    }

    @Test
    void factoryDoesNotDiscoverOrConnectBeforeTheAction() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        DataSource source = mock(DataSource.class);
        beans.registerSingleton("source", source);
        beans.registerSingleton("beanFactory", beans);
        var service = new BootUiEngineConfiguration()
                .bootUiMySqlInsightService(
                        beans.getBeanProvider(ListableBeanFactory.class),
                        mock(BootUiExposure.class),
                        new BootUiProperties());
        assertThat(service.report().status()).isEqualTo("NOT_READ");
        verifyNoInteractions(source);
    }

    private static MySqlRowLimits limits(BootUiProperties properties) {
        var mysql = properties.getMysql();
        return new MySqlRowLimits(
                mysql.getMaxSessions(),
                mysql.getMaxStatements(),
                mysql.getMaxIndexes(),
                mysql.getMaxTables(),
                mysql.getMaxLockWaits(),
                mysql.getMaxReplicationChannels(),
                mysql.getMaxSettings());
    }
}
