package bridgedebuggee;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The debuggee of {@code BridgeDebugInfoTests}, in a forked JVM under JDI: {@link #steps} makes, one per line, the calls
 * the inlined advice and the adapters make into the bridge inside a request's scope, as an instrumented method would.
 */
public final class StepDebuggee {

    static final String REQUEST = "00000000000000ab";

    private StepDebuggee() {}

    public static void main(String[] args) {
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "debuggee");
        request.put("mode", "dev");
        request.put("packages", List.of("bridgedebuggee"));
        request.put("sensors", List.of(CodePaths.SENSOR, CodeInventory.SENSOR));
        Supplier<Object> capture = () -> new Object[] {REQUEST, null, null, null, "/steps", null, null, 1L, 1L};
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        Map<String, Object> claimed = AgentBridge.claim(request, capture, reopen);
        int a = CodeInventory.methodId("bridgedebuggee.Shop#a()V");
        int b = CodeInventory.methodId("bridgedebuggee.Shop#b()V");
        // Loads and links everything the steps call before the debugger steps through them.
        warm(a, b);
        CodePaths.begin();
        int answer = steps(a, b);
        CodePaths.end();
        System.out.println("CLAIM=" + claimed.get("status") + " ANSWER=" + answer + " STATUS=" + CodePaths.status());
    }

    private static void warm(int a, int b) {
        CodePaths.begin();
        steps(a, b);
        CodePaths.end();
    }

    static int steps(int a, int b) {
        int outer = CodePaths.enter(a);
        CodeInventory.hit(a);
        int inner = CodePaths.enter(b);
        CodePaths.phase(CodePaths.PHASE_RESPONSE);
        CodePaths.exit(inner);
        int local = local(outer);
        CodePaths.exit(outer);
        return local + inner;
    }

    static int local(int value) {
        return value + 1;
    }
}
