package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentBridgeAccessTests {

    @AfterEach
    void reset() {
        Bridges.reset();
    }

    @Test
    void anAbsentBridgeAnswersUnavailableAndNeverThrows() {
        AgentBridgeAccess access = AgentBridgeAccess.absent();

        assertThat(access.present()).isFalse();
        assertThat(access.compatible()).isFalse();
        assertThat(access.protocol()).isNull();
        assertThat(access.attached()).isFalse();
        assertThat(access.status()).isEmpty();
        assertThat(access.claim(Map.of(), () -> null, snapshot -> () -> {})).containsEntry("status", "unavailable");
        assertThat(access.refine(1L, Map.of())).containsEntry("status", "unavailable");
        assertThat(access.disarm(1L)).containsEntry("status", "unavailable");
        assertThat(access.release("app", "dev")).containsEntry("status", "unavailable");
    }

    @Test
    void locateOnlyAsksTheBootstrapLoaderSoTheTestClassPathCopyIsNeverUsed() {
        assertThat(AgentBridge.class.getClassLoader())
                .as("the bridge is on the test class path")
                .isNotNull();

        AgentBridgeAccess located = AgentBridgeAccess.locate();

        assertThat(located.present()).isEqualTo(bootstrapHasBridge());
    }

    @Test
    void anotherProtocolIsPresentButIncompatibleWithBothVersionsInTheProblem() {
        AgentBridgeAccess access = new AgentBridgeAccess(FutureBridge.class);

        assertThat(access.present()).isTrue();
        assertThat(access.protocol()).isEqualTo(2);
        assertThat(access.compatible()).isFalse();
        assertThat(access.problem()).contains("protocol 2").contains("protocol 1");
        assertThat(access.status()).isEmpty();
        assertThat(access.claim(Map.of(), () -> null, snapshot -> () -> {}))
                .containsEntry("status", "unavailable")
                .containsEntry("reason", access.problem());
        assertThat(FutureBridge.calls)
                .as("an incompatible bridge is never called")
                .isZero();
    }

    @Test
    void theRealBridgeCarriesTheCodePathsSensorAndABridgeWithoutItLeavesItUnavailable() {
        assertThat(Bridges.access().codePathsSupported()).isTrue();

        AgentBridgeAccess future = new AgentBridgeAccess(FutureBridge.class);
        assertThat(future.codePathsSupported()).isFalse();
        assertThat(future.drainCodePaths(1L, blob -> {})).isZero();
        assertThat(future.excludeCodePathsMethod(1L, 1)).isFalse();
        assertThat(future.codePathsExcluded()).isEmpty();
        assertThat(AgentBridgeAccess.absent().codePathsSupported()).isFalse();
    }

    @Test
    void theRealBridgeSwitchesSensorsAndAnOlderBridgeOfTheSameProtocolSaysItPredatesThem() {
        assertThat(Bridges.access().sensorSwitchSupported()).isTrue();

        AgentBridgeAccess older = new AgentBridgeAccess(OlderBridge.class);
        assertThat(older.compatible()).isTrue();
        assertThat(older.sensorSwitchSupported()).isFalse();
        assertThat(older.switchSensor(1L, "environment", true))
                .containsEntry("status", "unavailable")
                .containsEntry("reason", "the attached BootUI agent predates runtime sensor switches");
        assertThat(AgentBridgeAccess.absent().switchSensor(1L, "environment", true))
                .containsEntry("status", "unavailable");
    }

    @Test
    void aBridgeMissingTheProtocolMethodsCannotBeBound() {
        AgentBridgeAccess access = new AgentBridgeAccess(HalfBridge.class);

        assertThat(access.present()).isTrue();
        assertThat(access.protocol()).isEqualTo(1);
        assertThat(access.compatible()).isFalse();
        assertThat(access.problem()).startsWith("the BootUI agent bridge could not be bound");
    }

    @Test
    void theRealBridgeIsBoundAndReportsWhetherAnAgentStarted() {
        AgentBridgeAccess access = Bridges.access();

        assertThat(access.compatible()).isTrue();
        assertThat(access.protocol()).isEqualTo(AgentBridge.PROTOCOL);
        assertThat(access.attached()).isFalse();
        assertThat(access.status()).containsEntry("attached", false).containsKey("counters");
        assertThat(access.claim(Map.of("application", "app"), () -> null, snapshot -> () -> {}))
                .containsEntry("status", "unavailable");

        Bridges.StubAgent.install();

        assertThat(access.attached()).isTrue();
        assertThat(access.status()).containsEntry("attached", true).containsKey("agent");
    }

    private static boolean bootstrapHasBridge() {
        try {
            Class.forName(AgentBridgeAccess.BRIDGE_CLASS, false, null);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /** A bridge of a future protocol. */
    public static final class FutureBridge {

        public static final int PROTOCOL = 2;

        static int calls;

        public static boolean attached() {
            calls++;
            return true;
        }

        public static Map<String, Object> status() {
            calls++;
            return Map.of("protocol", 2);
        }

        public static List<String> messages() {
            return List.of();
        }
    }

    /** A bridge of this protocol from before runtime sensor switches (M5-14). */
    public static final class OlderBridge {

        public static final int PROTOCOL = 1;

        public static boolean attached() {
            return true;
        }

        public static Map<String, Object> status() {
            return Map.of();
        }

        public static Map<String, Object> claim(
                Map<String, ?> request,
                java.util.function.Supplier<Object> capture,
                java.util.function.Function<Object, AutoCloseable> reopen) {
            return Map.of();
        }

        public static Map<String, Object> refine(long token, Map<String, ?> request) {
            return Map.of();
        }

        public static Map<String, Object> disarm(long token) {
            return Map.of();
        }

        public static Map<String, Object> release(String application, String mode) {
            return Map.of();
        }
    }

    /** A bridge of the right protocol without its methods. */
    public static final class HalfBridge {

        public static final int PROTOCOL = 1;
    }
}
