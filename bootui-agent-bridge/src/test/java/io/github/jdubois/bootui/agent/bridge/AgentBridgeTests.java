package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentBridgeTests {

    private final List<Map<String, Object>> calls = new ArrayList<>();

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
    void withoutAnAgentEveryOperationIsUnavailable() {
        AgentBridge.reset();
        AgentBridge.message("could not append the agent jar");

        Map<String, Object> result = AgentBridge.claim(request("shop", "dev"), capture(), reopen());

        assertThat(result.get("status")).isEqualTo(AgentBridge.UNAVAILABLE);
        assertThat((String) result.get("reason")).contains("could not append the agent jar");
        assertThat(AgentBridge.attached()).isFalse();
    }

    @Test
    void installIsSetOnce() {
        assertThat(AgentBridge.install(request -> null)).isFalse();
        assertThat(AgentBridge.attached()).isTrue();
    }

    @Test
    void firstClaimIsArmedAndTheAgentIsTold() {
        Map<String, Object> result = AgentBridge.claim(request("shop", "dev"), capture(), reopen());

        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        assertThat(result.get("token")).isInstanceOf(Long.class);
        assertThat(result.get("generation")).isEqualTo(1L);
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.get("op")).isEqualTo("claim");
            assertThat(call.get("application")).isEqualTo("shop");
            assertThat(call.get("packages")).isEqualTo(List.of("com.example.shop"));
        });
    }

    @Test
    void anotherApplicationIsHeldWhileTheClaimIsArmed() {
        Supplier<Object> capture = capture();
        Function<Object, AutoCloseable> reopen = reopen();
        AgentBridge.claim(request("shop", "dev"), capture, reopen);

        Map<String, Object> result = AgentBridge.claim(request("billing", "dev"), capture(), reopen());

        assertThat(result.get("status")).isEqualTo(AgentBridge.HELD);
        assertThat((String) result.get("reason")).contains("shop");
        assertThat(capture).isNotNull();
        assertThat(reopen).isNotNull();
    }

    @Test
    void theSameSlotAlwaysReplacesTheClaimEvenWhenItWasNeverDisarmed() {
        Supplier<Object> firstCapture = capture();
        Function<Object, AutoCloseable> firstReopen = reopen();
        long firstToken = token(AgentBridge.claim(request("shop", "dev"), firstCapture, firstReopen));

        Map<String, Object> restart = AgentBridge.claim(request("shop", "dev"), capture(), reopen());

        assertThat(restart.get("status")).isEqualTo(AgentBridge.ARMED);
        assertThat(restart.get("generation")).isEqualTo(2L);
        assertThat(AgentBridge.disarm(firstToken).get("status")).isEqualTo(AgentBridge.STALE);
        assertThat(firstCapture).isNotNull();
        assertThat(firstReopen).isNotNull();
    }

    @Test
    void devTakesOverTestButTestNeverTakesOverDev() {
        Supplier<Object> testCapture = capture();
        Function<Object, AutoCloseable> testReopen = reopen();
        AgentBridge.claim(request("shop", "test"), testCapture, testReopen);
        Supplier<Object> devCapture = capture();
        Function<Object, AutoCloseable> devReopen = reopen();

        assertThat(AgentBridge.claim(request("shop", "dev"), devCapture, devReopen)
                        .get("status"))
                .isEqualTo(AgentBridge.ARMED);
        assertThat(AgentBridge.claim(request("shop", "test"), capture(), reopen())
                        .get("status"))
                .isEqualTo(AgentBridge.HELD);
        assertThat(counter("takeovers")).isEqualTo(1L);
        assertThat(testCapture).isNotNull();
        assertThat(testReopen).isNotNull();
        assertThat(devCapture).isNotNull();
        assertThat(devReopen).isNotNull();
    }

    @Test
    void aDisarmedClaimIsTakenOverByAnyApplication() {
        long token = token(AgentBridge.claim(request("shop", "dev"), capture(), reopen()));

        assertThat(AgentBridge.disarm(token).get("status")).isEqualTo(AgentBridge.DISARMED);

        assertThat(AgentBridge.claim(request("billing", "dev"), capture(), reopen())
                        .get("status"))
                .isEqualTo(AgentBridge.ARMED);
        assertThat(calls).extracting(call -> call.get("op")).containsExactly("claim", "disarm", "claim");
    }

    @Test
    void anAbandonedClaimIsTakenOverOnceItsEngineIsCollected() throws InterruptedException {
        AgentBridge.claim(request("shop", "dev"), capture(), reopen());
        // Nothing but the bridge's weak reference holds this claim's capture now.
        for (int i = 0; i < 50 && !abandoned(); i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(abandoned()).isTrue();

        assertThat(AgentBridge.claim(request("billing", "dev"), capture(), reopen())
                        .get("status"))
                .isEqualTo(AgentBridge.ARMED);
    }

    @Test
    void refineMergesPackagesAndRejectsAStaleToken() {
        Supplier<Object> capture = capture();
        Function<Object, AutoCloseable> reopen = reopen();
        long token = token(AgentBridge.claim(request("shop", "dev"), capture, reopen));

        Map<String, Object> refined =
                AgentBridge.refine(token, Map.of("packages", List.of("com.example.shop", "com.example.shared")));

        assertThat(refined.get("status")).isEqualTo(AgentBridge.ARMED);
        assertThat(claimPackages()).containsExactly("com.example.shop", "com.example.shared");
        assertThat(AgentBridge.refine(token + 100, Map.of("packages", List.of("x.y")))
                        .get("status"))
                .isEqualTo(AgentBridge.STALE);
        assertThat(counter("staleTokens")).isEqualTo(1L);
        assertThat(capture).isNotNull();
        assertThat(reopen).isNotNull();
    }

    @Test
    void requestValuesAreCopied() {
        List<String> packages = new ArrayList<>(List.of("com.example.shop"));
        Map<String, Object> request = new LinkedHashMap<>(request("shop", "dev"));
        request.put("packages", packages);
        Supplier<Object> capture = capture();
        Function<Object, AutoCloseable> reopen = reopen();
        AgentBridge.claim(request, capture, reopen);

        packages.add("org.attacker");

        assertThat(claimPackages()).containsExactly("com.example.shop");
        assertThat(capture).isNotNull();
        assertThat(reopen).isNotNull();
    }

    @Test
    void releaseRemovesADisarmedClaimOfAnyApplicationButNotAnotherArmedOne() {
        Supplier<Object> capture = capture();
        Function<Object, AutoCloseable> reopen = reopen();
        long token = token(AgentBridge.claim(request("shop", "dev"), capture, reopen));

        assertThat(AgentBridge.release("billing", "dev").get("status")).isEqualTo(AgentBridge.HELD);
        assertThat(AgentBridge.release("shop", "dev").get("status")).isEqualTo(AgentBridge.RELEASED);
        assertThat(AgentBridge.status().get("claim")).isNull();
        assertThat(AgentBridge.disarm(token).get("status")).isEqualTo(AgentBridge.STALE);

        long next = token(AgentBridge.claim(request("billing", "dev"), capture(), reopen()));
        AgentBridge.disarm(next);
        assertThat(AgentBridge.release("shop", "dev").get("status")).isEqualTo(AgentBridge.RELEASED);
        assertThat(calls).extracting(call -> call.get("op")).contains("release");
        assertThat(capture).isNotNull();
        assertThat(reopen).isNotNull();
    }

    @Test
    void everyTransitionTheAgentSeesHasAHigherGenerationEvenAfterARelease() {
        AgentBridge.claim(request("shop", "dev"), capture(), reopen());
        AgentBridge.claim(request("shop", "dev"), capture(), reopen());
        AgentBridge.release("shop", "dev");
        Supplier<Object> capture = capture();
        Function<Object, AutoCloseable> reopen = reopen();

        Map<String, Object> reclaimed = AgentBridge.claim(request("shop", "dev"), capture, reopen);

        assertThat(reclaimed.get("status")).isEqualTo(AgentBridge.ARMED);
        assertThat(calls).extracting(call -> call.get("op")).containsExactly("claim", "claim", "release", "claim");
        assertThat(calls)
                .extracting(call -> (Long) call.get("generation"))
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(capture).isNotNull();
        assertThat(reopen).isNotNull();
    }

    @Test
    void anAgentFailureIsReportedAndCountedWithoutThrowing() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            throw new IllegalStateException("boom");
        });

        Map<String, Object> result = AgentBridge.claim(request("shop", "dev"), capture(), reopen());

        assertThat(result.get("status")).isEqualTo(AgentBridge.FAILED);
        assertThat((String) result.get("reason")).contains("boom");
        assertThat(counter("errors")).isPositive();
        assertThat(AgentBridge.messages())
                .anySatisfy(message -> assertThat(message).contains("boom"));
    }

    @Test
    void messagesKeepTheLastThirtyTwoInOrder() {
        for (int i = 0; i < 40; i++) {
            AgentBridge.message("m" + i);
        }

        assertThat(AgentBridge.messages()).hasSize(32).startsWith("m8").endsWith("m39");
    }

    private static Map<String, Object> request(String application, String mode) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", application);
        request.put("owner", application + "@test");
        request.put("mode", mode);
        request.put("packages", List.of("com.example." + application));
        return request;
    }

    /** A fresh capture each time, as the engine passes: never a cached non-capturing lambda. */
    private static Supplier<Object> capture() {
        Object marker = new Object();
        return () -> marker == null ? null : null;
    }

    private static Function<Object, AutoCloseable> reopen() {
        Object marker = new Object();
        return snapshot -> marker == null ? null : () -> {};
    }

    private static long token(Map<String, Object> result) {
        return (Long) result.get("token");
    }

    @SuppressWarnings("unchecked")
    private static boolean abandoned() {
        Map<String, Object> claim = (Map<String, Object>) AgentBridge.status().get("claim");
        return claim != null && Boolean.TRUE.equals(claim.get("abandoned"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> claimPackages() {
        Map<String, Object> claim = (Map<String, Object>) AgentBridge.status().get("claim");
        return (List<String>) claim.get("packages");
    }

    @SuppressWarnings("unchecked")
    private static long counter(String name) {
        return (Long) ((Map<String, Object>) AgentBridge.status().get("counters")).get(name);
    }
}
