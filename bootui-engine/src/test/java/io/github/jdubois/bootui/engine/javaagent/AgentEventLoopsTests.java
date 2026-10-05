package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.Blocking;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The adapters' hook into the agent's blocking sensor ({@code docs/PLAN-v2.md} §5.16, M5-5c). */
class AgentEventLoopsTests {

    @BeforeEach
    void install() throws Exception {
        reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void unbind() throws Exception {
        AgentEventLoops.rebind();
        reset();
    }

    @Test
    void withoutABridgeRegistrationDoesNothing() {
        AgentEventLoops.bindTo(null);

        assertThat(AgentEventLoops.bound()).isFalse();
        AgentEventLoops.register();
    }

    @Test
    void anAdapterRegistersTheEventLoopItClassifiesForTheClaimAskingForTheSensor() throws Exception {
        AgentEventLoops.bindTo(Blocking.class);
        AgentClaim claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("com.example"),
                AgentSensorSettings.defaults());
        try {
            Thread loop = new Thread(AgentEventLoops::register, "vert.x-eventloop-thread-0");
            loop.start();
            loop.join();

            assertThat(AgentEventLoops.bound()).isTrue();
            assertThat(AgentBridgeAccess.map(AgentBridge.status(), SideEffects.BLOCKING))
                    .containsEntry("eventLoopRegistrations", 1L)
                    .containsEntry("eventLoops", 1);
            assertThat(loop.getName()).isNotNull();
        } finally {
            claim.disarm();
        }
    }

    private static void reset() throws Exception {
        Method reset = AgentBridge.class.getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
    }
}
