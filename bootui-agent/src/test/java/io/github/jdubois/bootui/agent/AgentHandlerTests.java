package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bridge calls the agent outside any lock, so the handler orders transitions by generation (PLAN-v2 M5-1). */
class AgentHandlerTests {

    private final AgentHandler handler =
            new AgentHandler(null, AgentTestHook.NONE, "test", "javaagent", "agent.jar", 0);

    @Test
    void aClaimAfterAReleaseIsApplied() {
        apply("claim", 1);
        apply("claim", 2);
        apply("release", 3);
        apply("claim", 4);

        assertThat(status().get("armed")).isEqualTo(true);
        assertThat(status().get("generation")).isEqualTo(4L);
    }

    @Test
    void aReleaseDeliveredAfterANewerClaimIsIgnored() {
        apply("claim", 5);

        assertThat(apply("release", 4).get("status")).isEqualTo("ignored");
        assertThat(apply("claim", 3).get("status")).isEqualTo("ignored");
        assertThat(status().get("armed")).isEqualTo(true);
    }

    @Test
    void aDisarmOrRefineOfAnotherGenerationIsIgnored() {
        apply("claim", 7);
        apply("disarm", 6);
        Map<String, Object> refine = request("refine", 6);
        refine.put("packages", List.of("com.example.other"));
        handler.apply(refine);

        assertThat(status().get("armed")).isEqualTo(true);
        assertThat(status().get("packages")).isEqualTo(List.of("com.example.shop"));

        apply("disarm", 7);
        assertThat(status().get("armed")).isEqualTo(false);
    }

    @Test
    void anUnknownOperationFails() {
        assertThat(apply("explode", 1).get("status")).isEqualTo("failed");
    }

    private Map<String, Object> apply(String op, long generation) {
        return handler.apply(request(op, generation));
    }

    private static Map<String, Object> request(String op, long generation) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", op);
        request.put("generation", generation);
        request.put("packages", List.of("com.example.shop"));
        return request;
    }

    private Map<String, Object> status() {
        return handler.apply(request("status", 0));
    }
}
