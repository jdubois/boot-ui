package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.RequestValues;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorReport;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.javaagent.RequestValuesTesting;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sideeffectsapp.Launcher;

/**
 * {@code request-input-in-sink} as Security sinks rows ({@code docs/PLAN-v2.md} §5.16, §5.17, M5-6b), against the real
 * bridge: a concatenated statement is a row with its redacted, literal-masked text and a sentence worded as a fact; a
 * parameterized one is none; a match outside a literal or of digits only waits for a second request that confirms the
 * text varies with the value, and one that never varies is not shown; and no seeded value, 4-character ones included,
 * reaches any report.
 */
class SecuritySinksServiceTests {

    private static final String FIRST = "00000000000000ab";
    private static final String SECOND = "00000000000000cd";

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final AtomicLong clock = new AtomicLong();
    private final Map<String, String> routes = new LinkedHashMap<>();
    private final AgentEvidence evidence = new AgentEvidence(panel -> true, null);
    private SideEffectsService service;
    private AgentClaim claim;

    @BeforeEach
    void installAgent() {
        resetBridge();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
        clock.set(System.currentTimeMillis() - 60_000L);
        RequestValuesTesting.bind(RequestValues.class);
        AgentRequestValues.configure(true);
        routes.put(FIRST, "GET /api/sample/search");
        routes.put(SECOND, "GET /api/sample/search");
    }

    @AfterEach
    void resetAgent() {
        AgentRequestValues.configure(false);
        RequestValuesTesting.rebind();
        if (service != null) {
            service.close();
        }
        if (claim != null) {
            claim.disarm();
        }
        resetBridge();
    }

    @Test
    void aConcatenatedStatementIsARowWithItsRedactedTextAndAParameterizedOneIsNone() {
        start();
        String value = seeded("alice-smith");
        inRequest(FIRST, "name", value, () -> {
            Launcher.sql("select * from users where name = '" + value + "' and active = 1", context.get());
            Launcher.sql("select * from users where name = ?", context.get());
        });

        SideEffectsSensorReport report = service.sensor(SideEffectsCatalog.SECURITY_SINKS_ID, null, null);

        assertThat(report.sensor().state()).isEqualTo(SideEffectsSensorDto.RECORDING);
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(SideEffectsCatalog.SQL_TEXT);
            assertThat(row.target()).isEqualTo("select * from users where name = '{name}' and active = ?");
            assertThat(row.location()).isEqualTo(SideEffectsCatalog.INSIDE_LITERAL);
            assertThat(row.parameter()).isEqualTo("name");
            assertThat(row.callSite()).isEqualTo("sideeffectsapp.Launcher#sql");
            assertThat(row.attribution()).isEqualTo("GET /api/sample/search");
            assertThat(row.detail())
                    .startsWith("Request input reached this SQL text unchanged: the value of `name` appeared inside a"
                            + " literal. Check that it is bound as a parameter or escaped.")
                    .endsWith(SinkWording.SEEN_ONCE)
                    .doesNotContainIgnoringCase("vulnerab")
                    .doesNotContainIgnoringCase("injection");
            assertThat(row.totalMillis())
                    .as("no raw-text hash leaks as a duration")
                    .isZero();
        });
    }

    @Test
    void aSecondRequestWithAnotherValueConfirmsTheRow() {
        start();
        inRequest(
                FIRST,
                "name",
                seeded("alice-smith"),
                () -> Launcher.sql("select * from users where name = 'alice-smith'", context.get()));
        inRequest(
                SECOND,
                "name",
                seeded("bob-jones"),
                () -> Launcher.sql("select * from users where name = 'bob-jones'", context.get()));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.count()).isEqualTo(2L);
            assertThat(row.detail()).doesNotContain(SinkWording.SEEN_ONCE);
            assertThat(row.exemplarRequestIds()).containsExactlyInAnyOrder(FIRST, SECOND);
        });
    }

    @Test
    void aMatchOutsideALiteralWaitsForConfirmationAndOneThatNeverVariesIsNotShown() {
        start();
        // A column the statement always names: coincidental, never confirmed.
        inRequest(
                FIRST,
                "fields",
                seeded("status"),
                () -> Launcher.sql("select status from orders where id = 1", context.get()));
        inRequest(
                SECOND,
                "fields",
                seeded("status"),
                () -> Launcher.sql("select status from orders where id = 1", context.get()));
        // A concatenated ORDER BY column: shown once a second request varies it.
        inRequest(
                FIRST,
                "sort",
                seeded("created_at"),
                () -> Launcher.sql("select * from orders order by created_at", context.get()));
        assertThat(rows()).as("one request, outside a literal").isEmpty();
        inRequest(
                SECOND,
                "sort",
                seeded("updated_at"),
                () -> Launcher.sql("select * from orders order by updated_at", context.get()));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.target()).isEqualTo("select * from orders order by {sort}");
            assertThat(row.location()).isEqualTo(SideEffectsCatalog.OUTSIDE_LITERAL);
            assertThat(row.detail()).contains("outside a literal").doesNotContain(SinkWording.SEEN_ONCE);
        });
        assertThat(service.sensor(SideEffectsCatalog.SECURITY_SINKS_ID, null, null)
                        .limitations())
                .anyMatch(line -> line.startsWith("1 security-sinks match is not shown yet"));
    }

    @Test
    void aDigitsOnlyValueInsideALiteralAlsoWaitsForConfirmation() {
        start();
        inRequest(
                FIRST, "id", seeded("4242"), () -> Launcher.sql("select * from orders where id = 4242", context.get()));
        assertThat(rows()).isEmpty();
        inRequest(
                SECOND,
                "id",
                seeded("4343"),
                () -> Launcher.sql("select * from orders where id = 4343", context.get()));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.target()).isEqualTo("select * from orders where id = {id}");
            assertThat(row.location()).isEqualTo(SideEffectsCatalog.INSIDE_LITERAL);
        });
    }

    @Test
    void aStatementWithMoreMatchesThanCanBeRedactedKeepsNoText() {
        start();
        String value = seeded("alice-smith");
        StringBuilder sql = new StringBuilder("select * from users where");
        for (int i = 0; i < 9; i++) {
            sql.append(i == 0 ? "" : " or").append(" name = '").append(value).append("'");
        }
        inRequest(FIRST, "name", value, () -> Launcher.sql(sql.toString(), context.get()));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.target()).isEqualTo(SideEffectsService.TEXT_NOT_KEPT);
            assertThat(row.toString()).doesNotContain(value);
        });
        assertThat(service.sensor(SideEffectsCatalog.SECURITY_SINKS_ID, null, null)
                        .limitations())
                .anyMatch(line -> line.startsWith("Request-value matching: "));
    }

    @Test
    void workTheRequestHandedOffIsNeverChecked() {
        start();
        inRequest(FIRST, "name", seeded("alice-smith"), () -> {
            CorrelationContext propagated = new CorrelationContext(
                    FIRST, "async-00000000000000ef", null, null, null, null, null, null, null, false);
            Launcher.sql("select * from users where name = 'alice-smith'", propagated);
        });

        assertThat(rows()).isEmpty();
    }

    @Test
    void noSeededValueReachesAnyReportOrRowEvenAFourCharacterOne() {
        start();
        String four = seeded("abcd");
        String name = seeded("alice-smith");
        String file = seeded("q3-report");
        context.set(CorrelationContext.forRequest(FIRST));
        AgentRequestValues.begin(
                FIRST,
                new AgentRequestValues.Values()
                        .add("code", four)
                        .add("name", name)
                        .add("file", file));
        Launcher.sql("select * from t where code = '" + four + "' or name = '" + name + "'", context.get());
        Launcher.writeFile("/srv/reports/" + file + ".csv");
        Launcher.failedStart("/usr/bin/convert", "/srv/in/" + file + ".pdf");
        AgentRequestValues.end(FIRST);
        context.set(CorrelationContext.NONE);

        StringBuilder everything = new StringBuilder();
        everything.append(service.report());
        for (SideEffectsCatalog.Sensor sensor : SideEffectsCatalog.SENSORS) {
            everything.append(service.sensor(sensor.id(), null, null));
        }
        everything.append(service.agentReport(null, null));
        everything.append(service.modelAccesses());
        everything.append(service.status());
        String all = everything.toString();
        assertThat(rows()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(all).doesNotContain(four).doesNotContain(name).doesNotContain(file);
    }

    private List<SideEffectsRowDto> rows() {
        return service.sensor(SideEffectsCatalog.SECURITY_SINKS_ID, null, null).rows();
    }

    private void inRequest(String request, String name, String value, Runnable work) {
        context.set(CorrelationContext.forRequest(request));
        try {
            AgentRequestValues.begin(request, new AgentRequestValues.Values().add(name, value));
            work.run();
        } finally {
            AgentRequestValues.end(request);
            context.set(CorrelationContext.NONE);
        }
    }

    private static String seeded(String value) {
        return new String(value.toCharArray());
    }

    private void start() {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("sideeffectsapp"),
                new AgentSensorSettings(
                        List.of("processes", "files", AgentSensorSettings.SECURITY_SINKS),
                        List.of(),
                        List.of(),
                        null,
                        AgentSensorSettings.DEFAULT_RING_CAPACITY));
        claim.attach(new AgentHandoffs(context::get, null, null));
        SideEffects.enable(SideEffects.MASK_PROCESSES | SideEffects.MASK_FILES | SideEffects.MASK_SECURITY_SINKS);
        service = new SideEffectsService(
                AgentBridgeAccess.bind(AgentBridge.class),
                () -> claim,
                () -> null,
                id -> new JavaAgentService.SideEffectsCoverage(
                        SideEffectsSensorDto.RECORDING, null, List.of(), 0L, Map.of()),
                evidence,
                clock::get,
                "/home/someone");
        service.setRequestRoutes(ids -> {
            Map<String, String> named = new LinkedHashMap<>();
            for (String id : ids) {
                if (routes.containsKey(id)) {
                    named.put(id, routes.get(id));
                }
            }
            return named;
        });
        service.start();
    }

    private static void resetBridge() {
        try {
            Method reset = AgentBridge.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
