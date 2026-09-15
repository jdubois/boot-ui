package io.github.jdubois.bootui.webfluxsample;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Optional JDBC diagnostics alongside the reactive sample's existing H2 application database. */
@Configuration(proxyBeanMethods = false)
@Profile("mysql-diagnostics")
public class MySqlDiagnosticsConfiguration {

    @Bean(defaultCandidate = false, destroyMethod = "close")
    HikariDataSource mysqlDiagnosticsDataSource(Environment environment) {
        HikariDataSource source = new HikariDataSource();
        source.setPoolName("mysql-diagnostics");
        source.setJdbcUrl(environment.getRequiredProperty("bootui.sample.mysql.url"));
        source.setUsername(environment.getRequiredProperty("bootui.sample.mysql.username"));
        source.setPassword(environment.getRequiredProperty("bootui.sample.mysql.password"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setMaximumPoolSize(2);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(3000);
        return source;
    }
}
