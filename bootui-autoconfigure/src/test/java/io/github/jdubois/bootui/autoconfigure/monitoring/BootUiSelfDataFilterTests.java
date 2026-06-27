package io.github.jdubois.bootui.autoconfigure.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.telemetry.AttributeValue;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootUiSelfDataFilterTests {

    private final BootUiSelfDataFilter filter = BootUiSelfDataFilter.defaults();

    @Test
    void detectsInternalBootUiPackagesWithoutHidingSampleApplicationCode() {
        assertThat(filter.isBootUiClassOrResource("io.github.jdubois.bootui.autoconfigure.web.BeansController"))
                .isTrue();
        assertThat(filter.isBootUiClassOrResource("io.github.jdubois.bootui.core.BootUiDtos"))
                .isTrue();

        assertThat(filter.isBootUiClassOrResource("io.github.jdubois.bootui.sample.SampleApplication"))
                .isFalse();
        assertThat(filter.isBootUiLoggerName("io.github.jdubois.bootui.sample.SampleApplication"))
                .isFalse();
    }

    @Test
    void detectsBootUiPathsWithSegmentBoundaries() {
        assertThat(filter.isBootUiPath("/bootui/api/beans")).isTrue();
        assertThat(filter.isBootUiPath("http://localhost:8080/bootui/api/traces?limit=10"))
                .isTrue();

        assertThat(filter.isBootUiPath("/bootui-custom/api")).isFalse();
        assertThat(filter.isBootUiPath("/api/not-bootui")).isFalse();
    }

    @Test
    void supportsCustomBootUiPaths() {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/dev-console");
        properties.setApiPath("/dev-console/api");
        BootUiSelfDataFilter customFilter = new BootUiSelfDataFilter(properties);

        assertThat(customFilter.isBootUiPath("/dev-console/api/beans")).isTrue();
        assertThat(customFilter.isBootUiPath("/bootui/api/beans")).isTrue();
        assertThat(customFilter.isBootUiPath("/dev-console-extra/api")).isFalse();
    }

    @Test
    void disabledFilterIncludesSelfData() {
        assertThat(BootUiSelfDataFilter.disabled()
                        .shouldIncludeBean(
                                "bootUiAutoConfiguration",
                                io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration.class,
                                "io/github/jdubois/bootui/autoconfigure/BootUiAutoConfiguration.class"))
                .isTrue();
    }

    @Test
    void detectsMetersFromPathTagsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter self = Counter.builder("http.server.requests")
                .tag("uri", "/bootui/api/beans")
                .register(registry);
        Counter host = Counter.builder("http.server.requests")
                .tag("uri", "/api/orders")
                .register(registry);

        assertThat(filter.shouldIncludeMeter(self)).isFalse();
        assertThat(filter.shouldIncludeMeter(host)).isTrue();
    }

    @Test
    void treatsSelfOnlyOrRootSelfTraceAsBootUiData() {
        NormalizedSpan selfRoot = span("root", null, "GET /bootui/api/traces", "http.route", "/bootui/api/traces");
        NormalizedSpan hostChild = span("child", "root", "GET /api/orders", "http.route", "/api/orders");

        assertThat(filter.shouldIncludeTrace(List.of(selfRoot))).isFalse();
        assertThat(filter.shouldIncludeTrace(List.of(selfRoot, hostChild))).isFalse();
        assertThat(filter.shouldIncludeTrace(List.of(hostChild))).isTrue();
    }

    private static NormalizedSpan span(
            String spanId, String parentSpanId, String name, String attributeName, String attributeValue) {
        return new NormalizedSpan(
                "trace",
                spanId,
                parentSpanId,
                name,
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of(attributeName, AttributeValue.ofString(attributeValue)),
                List.of());
    }
}
