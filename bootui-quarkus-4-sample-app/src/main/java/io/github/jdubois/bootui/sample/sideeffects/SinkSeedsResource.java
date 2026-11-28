package io.github.jdubois.bootui.sample.sideeffects;

import io.github.jdubois.bootui.sample.restclient.SampleApiClient;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Security sinks' seeds on Quarkus ({@code docs/PLAN-v2.md} §5.16, §5.17 {@code request-input-in-sink}, M5-6b), as on
 * the Spring sample. With the BootUI agent's {@code security-sinks} sensor and {@code
 * bootui.agent.security-sinks.request-values=true}: {@code GET /api/sinks/search?name=…} pastes {@code name} into its
 * SQL text on a worker thread, a Security sinks row with the value redacted to {@code {name}}; its counterexample
 * {@code GET /api/sinks/search-bound?name=…} binds it and shows nothing. {@code GET /api/sinks/reports/{name}} reads a
 * file under {@code reports/{name}}, and {@code GET /api/sinks/lookup?name=…} calls a URL holding the value through the
 * recorded MicroProfile REST client. Quotes are doubled, so the seed is not exploitable.
 */
@Path("/api/sinks")
@Produces(MediaType.APPLICATION_JSON)
public class SinkSeedsResource {

    @Inject
    DataSource dataSource;

    @RestClient
    SampleApiClient apiClient;

    /** The seed: request input concatenated into SQL text. */
    @GET
    @Path("/search")
    public Map<String, Object> search(@QueryParam("name") @DefaultValue("alice") String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select id, total_cents from insight_orders where customer = '"
                        + name.replace("'", "''") + "'")) {
            return Map.of("rows", count(rows));
        }
    }

    /** The counterexample: the same query with the value bound as a parameter. */
    @GET
    @Path("/search-bound")
    public Map<String, Object> searchBound(@QueryParam("name") @DefaultValue("alice") String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("select id, total_cents from insight_orders where customer = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return Map.of("rows", count(rows));
            }
        }
    }

    /** Request input in a file path: a report read under {@code reports/{name}}. */
    @GET
    @Path("/reports/{name}")
    public Map<String, Object> report(@PathParam("name") String name) {
        java.nio.file.Path report =
                java.nio.file.Path.of("target", "bootui-side-effects", "reports", name.replace("/", "_") + ".csv");
        try (InputStream in = Files.newInputStream(report)) {
            return Map.of("bytes", in.readAllBytes().length);
        } catch (NoSuchFileException ex) {
            return Map.of("bytes", 0);
        } catch (IOException ex) {
            return Map.of("error", ex.getClass().getSimpleName());
        }
    }

    /** Request input in an outbound URL, through the recorded REST client. */
    @GET
    @Path("/lookup")
    public Map<String, String> lookup(@QueryParam("name") @DefaultValue("alice") String name) {
        String body = apiClient.listProductsFor(1, name);
        return Map.of("body", body == null ? "" : body);
    }

    private static int count(ResultSet rows) throws SQLException {
        int count = 0;
        while (rows.next()) {
            count++;
        }
        return count;
    }
}
