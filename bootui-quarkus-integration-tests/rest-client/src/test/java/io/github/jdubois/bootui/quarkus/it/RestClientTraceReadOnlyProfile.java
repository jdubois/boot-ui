package io.github.jdubois.bootui.quarkus.it;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public final class RestClientTraceReadOnlyProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("bootui.panels.rest-client-trace.read-only", "true");
    }
}
