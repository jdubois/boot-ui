package io.github.jdubois.bootui.console.activity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Wires the console's Live Activity storage/streaming beans. Split out from {@code
 * BootUiActivityConsoleApplication} to keep the entry point a plain {@code @SpringBootApplication}
 * class with no bean-definition noise, matching the sample apps' own convention of a bare {@code
 * public static void main} entry point.
 */
@Configuration
public class ConsoleActivityConfiguration {

    /**
     * The console's own durable store, built directly over the {@link DatabaseClient} Spring Boot's
     * R2DBC auto-configuration already provides from {@code spring.r2dbc.*} &mdash; no dedicated/shared
     * data-source distinction is needed here (unlike a host application's {@code
     * ActivityPersistenceSettings.DataSourceMode}) because the console's one and only job is to own this
     * database.
     */
    @Bean
    public ReactiveActivityStore activityStore(DatabaseClient databaseClient, ConsoleActivityProperties properties) {
        return new R2dbcActivityStore(databaseClient, properties.getTableName());
    }

    @Bean
    public ConsoleActivityChangeStream activityChangeStream() {
        return new ConsoleActivityChangeStream();
    }

    @Bean
    public ConsoleActivityForwardService activityForwardService(
            ReactiveActivityStore activityStore,
            ConsoleActivityProperties properties,
            ConsoleActivityChangeStream changeStream) {
        return new ConsoleActivityForwardService(activityStore, properties.getSharedSecret(), changeStream);
    }
}
