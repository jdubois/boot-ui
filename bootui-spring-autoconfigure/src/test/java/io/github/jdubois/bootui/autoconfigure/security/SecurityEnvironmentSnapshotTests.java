package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class SecurityEnvironmentSnapshotTests {
    @Test
    void nativeManagementPortAliasDoesNotCallItsEnvironmentOrHideOtherKeys() throws Exception {
        Environment callback = mock(Environment.class);
        Class<?> type = Class.forName(
                "org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration$LocalManagementPortPropertySource");
        var constructor = type.getDeclaredConstructor(Environment.class);
        constructor.setAccessible(true);
        var source = (PropertySource<?>) constructor.newInstance(callback);
        MockEnvironment environment = new MockEnvironment().withProperty("server.ssl.enabled", "true");
        environment.getPropertySources().addFirst(source);
        var captured = SecurityEnvironmentSnapshot.capture(environment);
        assertThat(captured.getProperty("server.ssl.enabled")).isEqualTo("true");
        assertThatThrownBy(() -> captured.getProperty("local.management.port"))
                .isInstanceOf(SecurityActuatorObservation.ObservationLimitException.class);
        verifyNoInteractions(callback);
    }

    @Test
    void nativeConfigLoaderWrapperIsCopiedWithoutLosingLocalProvenance() throws Exception {
        var source = new PropertiesPropertySourceLoader()
                .load(
                        "Config resource 'class path resource [reactive-security-bootstrap.properties]'",
                        new ClassPathResource("reactive-security-bootstrap.properties"))
                .get(0);
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(source);
        var captured = SecurityEnvironmentSnapshot.capture(environment);
        assertThat(captured.getProperty("security.audit.password")).isEqualTo("bootstrap-test-literal");
        assertThat(captured.getProperty("server.ssl.enabled")).isEqualTo("false");
    }

    @Test
    void wrappingAnOpaqueMapDoesNotMakeItsCallbacksSafe() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> custom = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                calls.incrementAndGet();
                throw new AssertionError("Application map callback must not run");
            }
        };
        MockEnvironment environment = new MockEnvironment().withProperty("server.ssl.enabled", "true");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("wrapped", Collections.unmodifiableMap(custom)));
        var captured = SecurityEnvironmentSnapshot.capture(environment);
        assertThatThrownBy(() -> captured.getProperty("server.ssl.enabled"))
                .isInstanceOf(SecurityActuatorObservation.ObservationLimitException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void opaqueSourcesAndValuesNeverExecuteAndBlockOnlySupportedEvidence() {
        AtomicInteger calls = new AtomicInteger();
        Object value = new Object() {
            @Override
            public String toString() {
                calls.incrementAndGet();
                throw new AssertionError("Application value callback must not run");
            }
        };
        MockEnvironment environment = new MockEnvironment().withProperty("server.ssl.enabled", "true");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("unknown-value", Map.of("application.password", value)));
        var captured = SecurityEnvironmentSnapshot.capture(environment);
        assertThat(captured.getProperty("server.ssl.enabled")).isEqualTo("true");
        assertThatThrownBy(() -> captured.getProperty("application.password"))
                .isInstanceOf(SecurityActuatorObservation.ObservationLimitException.class);
        environment.getPropertySources().addFirst(new PropertySource<Object>("opaque", new Object()) {
            @Override
            public Object getProperty(String name) {
                calls.incrementAndGet();
                throw new AssertionError("Application source callback must not run");
            }
        });
        var blocked = SecurityEnvironmentSnapshot.capture(environment);
        assertThatThrownBy(() -> blocked.getProperty("server.ssl.enabled"))
                .isInstanceOf(SecurityActuatorObservation.ObservationLimitException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void randomNamespaceIsBlockedWithoutObscuringOrdinaryConfiguration() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.ssl.enabled", "true");
        environment.getPropertySources().addFirst(new RandomValuePropertySource());
        var captured = SecurityEnvironmentSnapshot.capture(environment);
        assertThat(captured.getProperty("server.ssl.enabled")).isEqualTo("true");
        assertThatThrownBy(() -> captured.getProperty("random.uuid"))
                .isInstanceOf(SecurityActuatorObservation.ObservationLimitException.class);
    }

    @Test
    void eachCaptureObservesCurrentNativeMapValues() {
        var values = new LinkedHashMap<String, Object>();
        values.put("server.ssl.enabled", "false");
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("host", values));
        var initial = SecurityEnvironmentSnapshot.capture(environment);
        values.put("server.ssl.enabled", "true");
        assertThat(initial.getProperty("server.ssl.enabled")).isEqualTo("false");
        assertThat(SecurityEnvironmentSnapshot.capture(environment).getProperty("server.ssl.enabled"))
                .isEqualTo("true");
    }
}
