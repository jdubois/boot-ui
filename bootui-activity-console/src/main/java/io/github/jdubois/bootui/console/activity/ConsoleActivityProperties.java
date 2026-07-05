package io.github.jdubois.bootui.console.activity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The console's own small settings surface, distinct from a host application's {@code
 * bootui.activity.persistence.*}/{@code bootui.activity.forwarding.*} keys: the console is a
 * single-purpose receiver, not an adapter that can toggle between in-memory/JDBC/forwarding modes, so
 * it needs only the two settings below (everything else about its storage, such as the R2DBC
 * connection, is plain Spring Boot {@code spring.r2dbc.*} configuration).
 */
@ConfigurationProperties(prefix = "bootui.console.activity")
public class ConsoleActivityProperties {

    /** Table name for the console's own activity store. */
    private String tableName = "bootui_console_activity";

    /**
     * Optional shared secret senders must present via {@code X-BootUI-Forward-Token} to have their
     * batches accepted. {@code null}/blank (the default) accepts any request, relying on {@code
     * ConsoleSafetyFilter}'s loopback/Host-allow-list perimeter instead &mdash; the same zero-config
     * default every other BootUI mutating action uses.
     */
    private String sharedSecret;

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }
}
