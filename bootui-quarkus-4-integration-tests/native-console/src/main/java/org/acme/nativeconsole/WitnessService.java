package org.acme.nativeconsole;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

@ApplicationScoped
public class WitnessService {

    static final String LOGGER_NAME = "org.acme.nativeconsole.WitnessService";
    private static final Logger LOG = Logger.getLogger(LOGGER_NAME);
    private final AtomicInteger calls = new AtomicInteger();

    @Inject
    DataSource dataSource;

    @Inject
    OpenTelemetry telemetry;

    @Inject
    LabelService labels;

    public Witness witness() {
        int invocation = calls.incrementAndGet();
        LOG.info("native-acceptance-witness invocation=" + invocation + " password=native-acceptance-known-secret");
        Span span = telemetry
                .getTracer("native-acceptance-host")
                .spanBuilder("native-acceptance-witness")
                .startSpan();
        try {
            span.setAttribute("fixture.invocation", invocation);
            return new Witness(
                    "native-acceptance", new Detail("immutable-record", List.of(new Item(7, labels.label()))));
        } finally {
            span.end();
        }
    }

    public JdbcResult jdbc() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT ? AS NATIVE_WITNESS")) {
            statement.setInt(1, 42);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("The native acceptance query returned no row");
                }
                return new JdbcResult(result.getInt("NATIVE_WITNESS"), connection.getAutoCommit());
            }
        }
    }

    public void failure() {
        IllegalStateException failure = new IllegalStateException("native-acceptance-deliberate-failure");
        LOG.log(java.util.logging.Level.SEVERE, "native-acceptance-error-witness", failure);
        throw failure;
    }

    public int calls() {
        return calls.get();
    }

    public record Witness(String name, Detail detail) {}

    public record Detail(String kind, List<Item> items) {

        public Detail {
            items = List.copyOf(items);
        }
    }

    public record Item(int id, String label) {}

    public record JdbcResult(int value, boolean autoCommit) {}
}
