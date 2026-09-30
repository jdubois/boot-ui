package io.github.jdubois.bootui.quarkus.it;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.StreamingOutput;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Runs one query in its resource method and another while its response entity is written, as lazy loading during
 * serialization does, so {@link BootUiQuarkusRequestPhaseTest} can read the phase each one recorded.
 */
@Path("/it/sql-phases")
public class SqlPhaseProbeResource {

    @Inject
    DataSource dataSource;

    @GET
    @Blocking
    @Produces(MediaType.TEXT_PLAIN)
    public StreamingOutput run() throws Exception {
        query("SELECT 'in-handler'");
        return output -> {
            try {
                output.write(query("SELECT 'while-writing'").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        };
    }

    private String query(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
