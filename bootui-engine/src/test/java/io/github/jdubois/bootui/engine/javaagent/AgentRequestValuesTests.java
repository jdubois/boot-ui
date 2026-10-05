package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.RequestValues;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine's hooks into the request value holder ({@code docs/PLAN-v2.md} §5.16, M5-6b), against the real bridge:
 * the opt-in setting and the bridge's gate, BootUI's own query decoding, and the holder's answers through the hooks.
 */
class AgentRequestValuesTests {

    private static final String REQUEST = "00000000000000ab";

    private final List<Object> keep = new ArrayList<>();

    @BeforeEach
    void install() {
        Bridges.reset();
        Bridges.StubAgent.install();
        AgentRequestValues.bind(RequestValues.class);
    }

    @AfterEach
    void reset() {
        AgentRequestValues.configure(false);
        AgentRequestValues.rebind();
        Bridges.reset();
    }

    @Test
    void withoutTheAgentOrTheSettingNothingIsPushedOrMatched() {
        AgentRequestValues.bind(null);
        AgentRequestValues.configure(true);
        assertThat(AgentRequestValues.active()).isFalse();
        AgentRequestValues.begin(REQUEST, new AgentRequestValues.Values().add("name", "alice"));
        assertThat(AgentRequestValues.match("alice", AgentRequestValues.SINK_SQL, null, null))
                .isZero();
        assertThat(AgentRequestValues.status()).isEmpty();

        AgentRequestValues.bind(RequestValues.class);
        claim();
        sensor(true);
        AgentRequestValues.configure(false);
        assertThat(AgentRequestValues.active()).as("the setting is off").isFalse();
        AgentRequestValues.begin(REQUEST, new AgentRequestValues.Values().add("name", "alice"));
        assertThat(AgentRequestValues.status().get("live")).isEqualTo(0);
    }

    @Test
    void theHolderAnswersThroughTheHooksWithNamesAndSpansOnly() {
        claim();
        sensor(true);
        AgentRequestValues.configure(true);
        assertThat(AgentRequestValues.active()).isTrue();

        AgentRequestValues.begin(REQUEST, new AgentRequestValues.Values().addQuery("name=al%69ce&x=1"));
        int[] spans = new int[AgentRequestValues.SPANS_LENGTH];
        String[] names = new String[AgentRequestValues.MAX_VALUES];
        assertThat(AgentRequestValues.match("where name = 'alice'", AgentRequestValues.SINK_SQL, spans, names))
                .isEqualTo(1);
        assertThat(names[0]).isEqualTo("name");
        assertThat(spans[AgentRequestValues.S_COUNT]).isEqualTo(1);
        assertThat(AgentRequestValues.status().get("valuesTooShort")).isEqualTo(1L);

        AgentRequestValues.end(REQUEST);
        assertThat(AgentRequestValues.status().get("live")).isEqualTo(0);
    }

    @Test
    void queryStringsAreDecodedAsFormsAndBadPairsSkipped() {
        AgentRequestValues.Values values =
                new AgentRequestValues.Values().addQuery("a=hello+world&b=%E2%82%AC100&bad=%zz&flag&=anon&c=x%26y&&d=");

        assertThat(List.of(values.names())).containsExactly("a", "b", "", "c", "d");
        assertThat(List.of(values.values())).containsExactly("hello world", "€100", "anon", "x&y", "");
    }

    @Test
    void anAdapterPassesAtMostSixtyFourPairsAndReadsABoundedQuery() {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            query.append("p").append(i).append("=value").append(i).append('&');
        }
        assertThat(new AgentRequestValues.Values().addQuery(query.toString()).size())
                .isEqualTo(AgentRequestValues.MAX_PAIRS);
        String longQuery = "q=" + "x".repeat(AgentRequestValues.MAX_QUERY) + "&after=value";
        assertThat(List.of(new AgentRequestValues.Values().addQuery(longQuery).names()))
                .containsExactly("q");
    }

    @Test
    void mapsOfParametersAreFlattened() {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("tag", List.of("red", "blue"));
        Map<Object, Object> path = new LinkedHashMap<>();
        path.put("id", "1234");
        path.put(7, "ignored");
        AgentRequestValues.Values values = new AgentRequestValues.Values()
                .addAll(query)
                .addSingle(path)
                .addAll(null)
                .addSingle(null);

        assertThat(List.of(values.names())).containsExactly("tag", "tag", "id");
        assertThat(List.of(values.values())).containsExactly("red", "blue", "1234");
    }

    private void claim() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", List.of("executors"));
        Supplier<Object> capture = () -> new Object[] {REQUEST, null};
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        assertThat(AgentBridge.claim(request, capture, reopen).get("status")).isEqualTo("armed");
    }

    private static void sensor(boolean on) {
        try {
            Method sensor = RequestValues.class.getDeclaredMethod("sensor", boolean.class);
            sensor.setAccessible(true);
            sensor.invoke(null, on);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
