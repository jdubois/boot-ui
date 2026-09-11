package io.github.jdubois.bootui.autoconfigure.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.engine.telemetry.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExplorerRetainedPolicyTests {
    @Test
    void disablingBeanSourceAlsoHidesAlreadyCapturedLocalDetailFromTraces() {
        BootUiProperties properties = new BootUiProperties();
        SpringTelemetrySettings settings = new SpringTelemetrySettings(properties);
        TelemetryStore store = new TelemetryStore(settings);
        store.add(new NormalizedSpan(
                "trace",
                "http",
                null,
                "GET /orders",
                "SERVER",
                "app",
                "http",
                1,
                1000,
                "OK",
                null,
                Map.of(),
                List.of()));
        store.add(new NormalizedSpan(
                "trace",
                "local",
                "http",
                "orders.load()",
                "INTERNAL",
                "app",
                "bootui.explorer",
                2,
                900,
                "ERROR",
                null,
                Map.of("exception.type", AttributeValue.ofString("example.Failure")),
                List.of()));
        TracesService service =
                new TracesService(store, settings, new SelfTelemetryClassifier(true, "/bootui", "/bootui/api"));
        assertThat(service.detail("trace").orElseThrow().spans()).hasSize(2);
        properties.panel("exceptions").setEnabled(false);
        assertThat(service.detail("trace").orElseThrow().spans())
                .allSatisfy(span -> assertThat(span.attributes())
                        .noneMatch(attribute -> attribute.key().equals("exception.type")));
        for (String panel : List.of("beans", "activity", "explorer")) {
            properties.panel(panel).setEnabled(false);
            assertThat(service.detail("trace").orElseThrow().spans())
                    .singleElement()
                    .satisfies(span -> assertThat(span.scope()).isEqualTo("http"));
            properties.panel(panel).setEnabled(true);
        }
        assertThat(store.findTrace("trace").spans()).hasSize(2);
    }
}
