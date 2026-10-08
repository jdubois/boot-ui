package io.github.jdubois.bootui.sample.sideeffects;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Security sinks' seeds ({@code docs/PLAN-v2.md} §5.16, §5.17 {@code request-input-in-sink}, M5-6b). With the BootUI
 * agent's {@code security-sinks} sensor and {@code bootui.agent.security-sinks.request-values=true}: {@code GET
 * /api/sinks/search?name=…} pastes {@code name} into its SQL text, which Side Effects shows as a Security sinks row with
 * the value redacted to {@code {name}}; its counterexample {@code GET /api/sinks/search-bound?name=…} binds it as a
 * parameter and shows nothing. {@code GET /api/sinks/reports/{name}} reads a file under {@code reports/{name}}, and
 * {@code GET /api/sinks/lookup?name=…} calls a URL holding the value. Quotes are doubled, so the seed concatenates
 * without being exploitable; the value still appears verbatim, which is the fact the row states.
 */
@RestController
@RequestMapping("/api/sinks")
public class SinkSeedsController {

    private final JdbcTemplate jdbc;
    private final RestClient.Builder restClients;
    private final Environment environment;

    public SinkSeedsController(JdbcTemplate jdbc, RestClient.Builder restClients, Environment environment) {
        this.jdbc = jdbc;
        this.restClients = restClients;
        this.environment = environment;
    }

    /** The seed: request input concatenated into SQL text. */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam(name = "name", defaultValue = "alice") String name) {
        return jdbc.queryForList(
                "select id, total_cents from insight_orders where customer = '" + name.replace("'", "''") + "'");
    }

    /** The counterexample: the same query with the value bound as a parameter. */
    @GetMapping("/search-bound")
    public List<Map<String, Object>> searchBound(@RequestParam(name = "name", defaultValue = "alice") String name) {
        return jdbc.queryForList("select id, total_cents from insight_orders where customer = ?", name);
    }

    /** Request input in a file path: a report read under {@code reports/{name}}. */
    @GetMapping("/reports/{name}")
    public Map<String, Object> report(@PathVariable String name) {
        Path report = Path.of("target", "bootui-side-effects", "reports", name.replace("/", "_") + ".csv");
        try (InputStream in = Files.newInputStream(report)) {
            return Map.of("bytes", in.readAllBytes().length);
        } catch (NoSuchFileException ex) {
            return Map.of("bytes", 0);
        } catch (IOException ex) {
            return Map.of("error", ex.getClass().getSimpleName());
        }
    }

    /** Request input in an outbound URL, through the recorded REST client. */
    @GetMapping("/lookup")
    public Map<String, String> lookup(@RequestParam(name = "name", defaultValue = "alice") String name) {
        String body = restClients
                .build()
                .get()
                .uri("http://localhost:" + port() + "/api/side-effects/runtime-version?user={name}", name)
                .retrieve()
                .body(String.class);
        return Map.of("body", body == null ? "" : body);
    }

    private int port() {
        return Integer.parseInt(
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")));
    }
}
