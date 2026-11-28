package org.acme.sqltrace;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

@Path("/it/sql-trace/{id}")
public class SqlTraceProbeResource {

    @Inject
    DataSource dataSource;

    @GET
    @Blocking
    @Produces(MediaType.TEXT_PLAIN)
    public String query(@PathParam("id") int id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT ? AS bootui_http_marker")) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("The HTTP SQL fixture returned no row");
                }
                return Integer.toString(rows.getInt(1));
            }
        }
    }
}
