package bootuiagentit;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runtime sensor switches in a forked JVM beside the agent (PLAN-v2 M5-14): a claim with the {@code processes} sensor
 * only switches {@code environment} and {@code threads} on, which the agent installs and self-tests without a new claim,
 * then off, which removes their hooks; a switch survives the same application's next claim, and a stale token switches
 * nothing; a sensor the application's configuration asks for is switched off and back on as well. Prints one PASS or FAIL
 * line per behavior, then the bridge's status.
 */
public final class SensorSwitchBehaviors {

    static final String PROPERTY = "bootui.it.switch.title";

    private SensorSwitchBehaviors() {}

    public static void main(String[] args) throws Exception {
        FilesEnvironmentBehaviors.work = Path.of("")
                .toAbsolutePath()
                .resolve("sensor-switch-" + ProcessHandle.current().pid());
        Files.createDirectories(FilesEnvironmentBehaviors.work);
        // Loaded and used before the claim, as an application's are: the hooks retransform it.
        System.getProperty(PROPERTY);
        long token = FilesEnvironmentBehaviors.claim(List.of(SideEffects.PROCESSES));
        FilesEnvironmentBehaviors.token = token;
        FilesEnvironmentBehaviors.awaitSelfTest(SideEffects.PROCESSES);

        readProperty("before");
        FilesEnvironmentBehaviors.drain();
        check(
                "before the switch, a property read records nothing",
                FilesEnvironmentBehaviors.find(SideEffects.KIND_SYSTEM_PROPERTY, PROPERTY + ".before") == null);

        Map<String, Object> on = AgentBridge.switchSensor(token, SideEffects.ENVIRONMENT, true);
        Object environment = SideEffectsBehaviors.awaitState(SideEffects.ENVIRONMENT, "installed");
        readProperty("on");
        long[] read = FilesEnvironmentBehaviors.await(
                FilesEnvironmentBehaviors.target(SideEffects.KIND_SYSTEM_PROPERTY, PROPERTY + ".on"));
        check(
                "switching environment on installs and self-tests it in this run, which records a read (" + on + ", "
                        + environment + ")",
                AgentBridge.ARMED.equals(on.get("status"))
                        && "installed".equals(environment)
                        && Boolean.TRUE.equals(SideEffectsBehaviors.sensor(SideEffects.ENVIRONMENT)
                                .get("active"))
                        && read != null
                        && read[SideEffects.R_REQUEST] == FilesEnvironmentBehaviors.REQUEST_BITS
                        && Boolean.TRUE.equals(SideEffectsBehaviors.sensor(SideEffects.PROCESSES)
                                .get("active")));

        Map<String, Object> threadsOn = AgentBridge.switchSensor(token, "threads", true);
        Object threads = awaitThreads("installed");
        check(
                "switching threads on installs and self-tests it in this run (" + threadsOn + ", " + threads + ")",
                AgentBridge.ARMED.equals(threadsOn.get("status"))
                        && "installed".equals(threads)
                        && Boolean.TRUE.equals(
                                SideEffectsBehaviors.sensor("threads").get("active")));

        Map<String, Object> off = AgentBridge.switchSensor(token, SideEffects.ENVIRONMENT, false);
        readProperty("off");
        FilesEnvironmentBehaviors.drain();
        boolean stoppedAtOnce =
                FilesEnvironmentBehaviors.find(SideEffects.KIND_SYSTEM_PROPERTY, PROPERTY + ".off") == null;
        Object processes = SideEffectsBehaviors.awaitState(SideEffects.PROCESSES, "installed");
        SideEffects.beginSelfTest();
        System.getProperty(PROPERTY);
        Map<String, Object> hits = SideEffects.endSelfTest();
        check(
                "switching environment off stops its recording at once and removes its hooks, processes recording on ("
                        + off + ", " + processes + ", " + hits + ")",
                AgentBridge.ARMED.equals(off.get("status"))
                        && stoppedAtOnce
                        && "installed".equals(processes)
                        && Long.valueOf(0L).equals(hits.get("System.getProperty"))
                        && !Boolean.TRUE.equals(SideEffectsBehaviors.sensor(SideEffects.ENVIRONMENT)
                                .get("active")));

        Map<String, Object> threadsOff = AgentBridge.switchSensor(token, "threads", false);
        threads = awaitThreads("released");
        check(
                "switching threads off restores java.lang.Thread (" + threadsOff + ", " + threads + ")",
                AgentBridge.ARMED.equals(threadsOff.get("status")) && "released".equals(threads));

        AgentBridge.switchSensor(token, SideEffects.ENVIRONMENT, true);
        SideEffectsBehaviors.awaitState(SideEffects.ENVIRONMENT, "installed");
        AgentBridge.disarm(token);
        long restarted = FilesEnvironmentBehaviors.claim(List.of(SideEffects.PROCESSES));
        FilesEnvironmentBehaviors.token = restarted;
        Map<String, Object> stale = AgentBridge.switchSensor(token, SideEffects.FILES, true);
        environment = SideEffectsBehaviors.awaitState(SideEffects.ENVIRONMENT, "installed");
        readProperty("restarted");
        long[] again = FilesEnvironmentBehaviors.await(
                FilesEnvironmentBehaviors.target(SideEffects.KIND_SYSTEM_PROPERTY, PROPERTY + ".restarted"));
        check(
                "the same application's next claim keeps the switch, and the previous run's token switches nothing ("
                        + stale + ", " + environment + ")",
                AgentBridge.STALE.equals(stale.get("status"))
                        && "installed".equals(environment)
                        && Boolean.TRUE.equals(SideEffectsBehaviors.sensor(SideEffects.ENVIRONMENT)
                                .get("active"))
                        && !Boolean.TRUE.equals(
                                SideEffectsBehaviors.sensor(SideEffects.FILES).get("active"))
                        && again != null);

        // A sensor on in the application's own configuration, as environment is by default (D49): switched off and back
        // on at run time, the claim returning to its configuration.
        AgentBridge.disarm(restarted);
        long configured = FilesEnvironmentBehaviors.claim(List.of(SideEffects.PROCESSES, SideEffects.ENVIRONMENT));
        FilesEnvironmentBehaviors.token = configured;
        SideEffectsBehaviors.awaitState(SideEffects.ENVIRONMENT, "installed");
        Map<String, Object> configuredOff = AgentBridge.switchSensor(configured, SideEffects.ENVIRONMENT, false);
        Object released = SideEffectsBehaviors.awaitState(SideEffects.ENVIRONMENT, "released");
        readProperty("configured-off");
        FilesEnvironmentBehaviors.drain();
        boolean silent =
                FilesEnvironmentBehaviors.find(SideEffects.KIND_SYSTEM_PROPERTY, PROPERTY + ".configured-off") == null;
        Map<String, Object> configuredOn = AgentBridge.switchSensor(configured, SideEffects.ENVIRONMENT, true);
        Object reinstalled = SideEffectsBehaviors.awaitState(SideEffects.ENVIRONMENT, "installed");
        readProperty("configured-on");
        long[] recordedAgain = FilesEnvironmentBehaviors.await(
                FilesEnvironmentBehaviors.target(SideEffects.KIND_SYSTEM_PROPERTY, PROPERTY + ".configured-on"));
        check(
                "a configured sensor switched off records nothing and releases its hooks, and switched back on records"
                        + " again (" + configuredOff + ", " + released + ", " + configuredOn + ", " + reinstalled + ")",
                AgentBridge.ARMED.equals(configuredOff.get("status"))
                        && "released".equals(released)
                        && silent
                        && AgentBridge.ARMED.equals(configuredOn.get("status"))
                        && "installed".equals(reinstalled)
                        && recordedAgain != null
                        && Boolean.TRUE.equals(SideEffectsBehaviors.sensor(SideEffects.PROCESSES)
                                .get("active")));

        FilesEnvironmentBehaviors.RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + AgentBridge.status());
    }

    static void readProperty(String suffix) {
        FilesEnvironmentBehaviors.CONTEXT.set(FilesEnvironmentBehaviors.REQUEST);
        System.getProperty(PROPERTY + "." + suffix);
        FilesEnvironmentBehaviors.CONTEXT.remove();
        FilesEnvironmentBehaviors.RECORDS.clear();
    }

    static Object awaitThreads(String expected) throws Exception {
        Object state = null;
        for (int i = 0; i < 400; i++) {
            state = SideEffectsBehaviors.sensor("threads").get("state");
            if (expected.equals(state)) {
                return state;
            }
            Thread.sleep(25);
        }
        return state;
    }

    static void check(String name, boolean ok) {
        FilesEnvironmentBehaviors.check(name, ok);
    }
}
