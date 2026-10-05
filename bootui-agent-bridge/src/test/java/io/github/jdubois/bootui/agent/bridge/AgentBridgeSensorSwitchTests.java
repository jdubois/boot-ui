package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runtime sensor switches (PLAN-v2 M5-14): token-guarded, kept per slot across claims, never across a JVM. */
class AgentBridgeSensorSwitchTests {

    private final List<Map<String, Object>> calls = new ArrayList<>();
    // Strongly held, so no claim of these tests is ever abandoned.
    private final List<Object> held = new ArrayList<>();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            calls.add(request);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void switchingASensorOnKeepsTheRunAndTellsTheAgentInOrder() {
        Map<String, Object> claimed = claim("shop", "dev", "processes");
        long token = token(claimed);
        calls.clear();

        Map<String, Object> result = AgentBridge.switchSensor(token, "environment", true);

        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        assertThat(result.get("token")).isEqualTo(token);
        assertThat(result.get("generation")).isEqualTo(claimed.get("generation"));
        Map<String, Object> claim = claim(result);
        assertThat(claim.get("sensors")).isEqualTo(List.of("processes", "environment"));
        assertThat(claim.get("configuredSensors")).isEqualTo(List.of("processes"));
        assertThat(claim.get("sensorOverrides")).isEqualTo(Map.of("environment", true));
        assertThat(claim.get("sensorsRevision")).isEqualTo(1L);
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.get("op")).isEqualTo("sensors");
            assertThat(call.get("generation")).isEqualTo(claimed.get("generation"));
            assertThat(call.get("sensors")).isEqualTo(List.of("processes", "environment"));
            assertThat(call.get("sensorsRevision")).isEqualTo(1L);
        });
        assertThat(AgentBridge.status()).containsKey("environment");
    }

    @Test
    void switchingBackToTheConfigurationDropsTheOverride() {
        long token = token(claim("shop", "dev", "processes"));
        AgentBridge.switchSensor(token, "environment", true);

        Map<String, Object> claim = claim(AgentBridge.switchSensor(token, "environment", false));

        assertThat(claim.get("sensors")).isEqualTo(List.of("processes"));
        assertThat(claim.get("sensorOverrides")).isEqualTo(Map.of());
        assertThat(claim.get("sensorsRevision")).isEqualTo(2L);
    }

    @Test
    void aConfiguredSensorCanBeSwitchedOff() {
        long token = token(claim("shop", "dev", "processes", "environment"));

        Map<String, Object> claim = claim(AgentBridge.switchSensor(token, "environment", false));

        assertThat(claim.get("sensors")).isEqualTo(List.of("processes"));
        assertThat(claim.get("sensorOverrides")).isEqualTo(Map.of("environment", false));
    }

    @Test
    void aStaleOrEndedTokenSwitchesNothing() {
        long token = token(claim("shop", "dev", "processes"));
        calls.clear();

        assertThat(AgentBridge.switchSensor(token + 1, "environment", true).get("status"))
                .isEqualTo(AgentBridge.STALE);
        AgentBridge.disarm(token);
        calls.clear();
        assertThat(AgentBridge.switchSensor(token, "environment", true).get("status"))
                .isEqualTo(AgentBridge.STALE);
        assertThat(calls).isEmpty();
        assertThat(claimNow().get("sensorOverrides")).isEqualTo(Map.of());
    }

    @Test
    void onlyTheOptInThreadsFilesAndEnvironmentSensorsCanBeSwitched() {
        long token = token(claim("shop", "dev", "processes"));
        calls.clear();

        for (String sensor : new String[] {
            "executors", "inventory", "code-paths", "processes", "network", "blocking", "caught-exceptions", "x", null
        }) {
            Map<String, Object> result = AgentBridge.switchSensor(token, sensor, true);
            assertThat(result.get("status")).as(sensor).isEqualTo(AgentBridge.FAILED);
        }
        assertThat(calls).isEmpty();
        assertThat(AgentBridge.switchable("threads")).isTrue();
        assertThat(AgentBridge.switchable("files")).isTrue();
        assertThat(AgentBridge.switchable("environment")).isTrue();
        assertThat(claimNow().get("sensorOverrides")).isEqualTo(Map.of());
    }

    @Test
    void withoutAnAgentASwitchIsUnavailable() {
        AgentBridge.reset();

        assertThat(AgentBridge.switchSensor(1L, "environment", true).get("status"))
                .isEqualTo(AgentBridge.UNAVAILABLE);
    }

    @Test
    void aRestartInTheSameSlotKeepsTheSwitch() {
        long token = token(claim("shop", "dev", "processes"));
        AgentBridge.switchSensor(token, "environment", true);
        AgentBridge.disarm(token);
        calls.clear();

        Map<String, Object> restart = claim("shop", "dev", "processes");

        Map<String, Object> claim = claim(restart);
        assertThat(claim.get("sensors")).isEqualTo(List.of("processes", "environment"));
        assertThat(claim.get("sensorOverrides")).isEqualTo(Map.of("environment", true));
        assertThat(claim.get("sensorsRevision")).isEqualTo(0L);
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.get("op")).isEqualTo("claim");
            assertThat(call.get("sensors")).isEqualTo(List.of("processes", "environment"));
        });
    }

    @Test
    void aConfigurationThatAgreesWithASwitchDropsIt() {
        long token = token(claim("shop", "dev", "processes"));
        AgentBridge.switchSensor(token, "environment", true);

        Map<String, Object> agreeing = claim(claim("shop", "dev", "processes", "environment"));
        assertThat(agreeing.get("sensorOverrides")).isEqualTo(Map.of());

        Map<String, Object> changedBack = claim(claim("shop", "dev", "processes"));
        assertThat(changedBack.get("sensors")).isEqualTo(List.of("processes"));
    }

    @Test
    void switchesOutliveAReleaseAndAnotherApplicationsClaimButNotATestRun() {
        long token = token(claim("shop", "dev", "processes"));
        AgentBridge.switchSensor(token, "environment", true);
        AgentBridge.disarm(token);

        long billing = token(claim("billing", "dev", "processes"));
        assertThat(claimNow().get("sensors")).isEqualTo(List.of("processes"));
        AgentBridge.disarm(billing);
        assertThat(claim(claim("shop", "test", "processes")).get("sensors")).isEqualTo(List.of("processes"));
        long again = token(claim("shop", "dev", "processes"));
        assertThat(claimNow().get("sensors")).isEqualTo(List.of("processes", "environment"));

        AgentBridge.disarm(again);
        AgentBridge.release("shop", "dev");
        assertThat(claim(claim("shop", "dev", "processes")).get("sensors"))
                .isEqualTo(List.of("processes", "environment"));
    }

    @Test
    void theThreadsSensorIsNotSwitchedBackOnInTheRunItFailedIn() {
        Map<String, Object> claimed = claim("shop", "dev", "threads");
        long token = token(claimed);
        ThreadPropagation.disable((Long) claimed.get("generation"), false);
        assertThat(claim(AgentBridge.switchSensor(token, "threads", false)).get("sensors"))
                .isEqualTo(List.of());

        Map<String, Object> refused = AgentBridge.switchSensor(token, "threads", true);

        assertThat(refused.get("status")).isEqualTo(AgentBridge.FAILED);
        assertThat((String) refused.get("reason")).contains("until the application restarts");
        assertThat(claimNow().get("sensorOverrides")).isEqualTo(Map.of("threads", false));
    }

    @Test
    void aLateSaveOfAnOlderClaimNeverOverwritesANewerOne() {
        Claim older = claimOf(5L, 3L, Map.of("environment", true));
        Claim newer = claimOf(6L, 1L, Map.of("files", true));

        SlotSwitches.save(newer);
        SlotSwitches.save(older);

        assertThat(SlotSwitches.saved("dev:shop")).isEqualTo(Map.of("files", true));
        SlotSwitches.save(claimOf(6L, 2L, Collections.<String, Boolean>emptyMap()));
        assertThat(SlotSwitches.saved("dev:shop")).isEmpty();
    }

    @Test
    void theBridgeKeepsTheSwitchesOfAtMostEightSlots() {
        for (int i = 0; i < SlotSwitches.MAX_SLOTS + 2; i++) {
            SlotSwitches.save(new Claim(
                    i + 1L,
                    i + 1L,
                    "owner",
                    "app" + i,
                    "dev",
                    List.of(),
                    List.of(),
                    Map.of("environment", true),
                    0L,
                    new String[0],
                    new String[0],
                    1024,
                    List.of(),
                    0,
                    0L,
                    0L,
                    false,
                    new WeakReference<Supplier<Object>>(null),
                    new WeakReference<Function<Object, AutoCloseable>>(null)));
        }

        assertThat(SlotSwitches.saved("dev:app0")).isEmpty();
        assertThat(SlotSwitches.saved("dev:app1")).isEmpty();
        assertThat(SlotSwitches.saved("dev:app" + (SlotSwitches.MAX_SLOTS + 1))).isEqualTo(Map.of("environment", true));
    }

    private static Claim claimOf(long generation, long revision, Map<String, Boolean> overrides) {
        return new Claim(
                generation,
                generation,
                "owner",
                "shop",
                "dev",
                List.of(),
                List.of(),
                overrides,
                revision,
                new String[0],
                new String[0],
                1024,
                List.of(),
                0,
                0L,
                0L,
                false,
                new WeakReference<Supplier<Object>>(null),
                new WeakReference<Function<Object, AutoCloseable>>(null));
    }

    private Map<String, Object> claim(String application, String mode, String... sensors) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", application);
        request.put("owner", application + "@" + mode);
        request.put("mode", mode);
        request.put("packages", List.of("com.example." + application));
        request.put("sensors", List.of(sensors));
        Object marker = new Object();
        Supplier<Object> capture = () -> marker == null ? null : null;
        Function<Object, AutoCloseable> reopen = snapshot -> marker == null ? null : () -> {};
        held.add(capture);
        held.add(reopen);
        return AgentBridge.claim(request, capture, reopen);
    }

    private static long token(Map<String, Object> result) {
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        return (Long) result.get("token");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> claim(Map<String, Object> result) {
        return (Map<String, Object>) result.get("claim");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> claimNow() {
        return (Map<String, Object>) AgentBridge.status().get("claim");
    }
}
