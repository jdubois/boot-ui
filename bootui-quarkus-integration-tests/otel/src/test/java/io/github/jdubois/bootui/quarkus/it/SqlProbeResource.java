package io.github.jdubois.bootui.quarkus.it;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * A minimal application endpoint used only by {@link BootUiQuarkusLiveActivityCorrelationTest}. It is
 * deliberately {@link Blocking} so the JDBC call runs on a worker thread — the exact event-loop→worker hop
 * that makes Spring's thread-per-request correlation unportable and that OpenTelemetry's context propagation
 * crosses. The injected {@link DataSource} resolves to BootUI's SQL-tracing wrapper (the {@code @Alternative}
 * {@code tracedDataSource}), so this single {@code SELECT} produces both an HTTP request entry and a nested
 * SQL entry in the Live Activity feed, sharing the request's trace id.
 */
@Path("/it/sql")
public class SqlProbeResource {

    @Inject
    DataSource dataSource;

    @GET
    @Blocking
    @Produces(MediaType.TEXT_PLAIN)
    public String runQuery() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 42")) {
            resultSet.next();
            return "ok:" + resultSet.getInt(1);
        }
    }
}
