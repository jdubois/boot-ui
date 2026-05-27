package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint.LoggerLevelsDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint.LoggersDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint.SingleLoggerLevelsDescriptor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Mutation-focused tests for {@link LoggersController}.
 *
 * <p>Complements {@code LoggersControllerTests} by covering scenarios that involve
 * reading back the effect of a level-change through a subsequent GET, clearing a
 * level via a JSON {@code null} value, and the round-trip from POST clear to GET
 * showing only an inherited effective level.</p>
 */
class LoggersControllerMutationTests {

    // ── set level → GET reflects it ──────────────────────────────────────────

    @Test
    void setLevel_thenGetAll_reflectsNewConfiguredLevel() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);

        // POST: setting DEBUG should return the refreshed DTO
        when(endpoint.loggerLevels("com.example"))
                .thenReturn(new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example", LogLevel.DEBUG, LogLevel.DEBUG)));

        // GET all: build a LoggersDescriptor that includes the newly-configured logger
        Map<String, LoggerLevelsDescriptor> loggersMap = new LinkedHashMap<>();
        loggersMap.put("com.example", new SingleLoggerLevelsDescriptor(
                new LoggerConfiguration("com.example", LogLevel.DEBUG, LogLevel.DEBUG)));
        TreeSet<LogLevel> levels = new TreeSet<>();
        levels.addAll(java.util.List.of(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO,
                LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL, LogLevel.OFF));
        when(endpoint.loggers()).thenReturn(new LoggersDescriptor(levels, loggersMap, Map.of()));

        MockMvc mvc = standaloneSetup(new LoggersController(providerOf(endpoint))).build();

        // POST to configure the level
        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"DEBUG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("com.example"))
                .andExpect(jsonPath("$.configuredLevel").value("DEBUG"))
                .andExpect(jsonPath("$.effectiveLevel").value("DEBUG"));

        // GET: the full list must contain the updated entry
        mvc.perform(get("/bootui/api/loggers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggers[?(@.name=='com.example')].configuredLevel").value("DEBUG"));

        verify(endpoint, times(1)).configureLogLevel(eq("com.example"), eq(LogLevel.DEBUG));
    }

    // ── clear level with explicit null JSON field ─────────────────────────────

    @Test
    void clearLevel_withNullLevelJson_callsConfigureWithNullLevel() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        // After clearing, the logger has no configured level but inherits INFO
        when(endpoint.loggerLevels("com.example"))
                .thenReturn(new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example", (LogLevel) null, LogLevel.INFO)));

        MockMvc mvc = standaloneSetup(new LoggersController(providerOf(endpoint))).build();

        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("com.example"))
                .andExpect(jsonPath("$.configuredLevel").doesNotExist())
                .andExpect(jsonPath("$.effectiveLevel").value("INFO"));

        verify(endpoint, times(1)).configureLogLevel(eq("com.example"), eq((LogLevel) null));
    }

    // ── clear level → GET shows only inherited effective level ────────────────

    @Test
    void clearLevel_thenGetAll_showsNullConfiguredAndInheritedEffectiveLevel() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);

        // POST clear (blank level): returns the logger without a configured level
        when(endpoint.loggerLevels("com.example"))
                .thenReturn(new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example", (LogLevel) null, LogLevel.WARN)));

        // GET all: the descriptor also shows null configured, WARN effective
        Map<String, LoggerLevelsDescriptor> loggersMap = new LinkedHashMap<>();
        loggersMap.put("com.example", new SingleLoggerLevelsDescriptor(
                new LoggerConfiguration("com.example", (LogLevel) null, LogLevel.WARN)));
        when(endpoint.loggers())
                .thenReturn(new LoggersDescriptor(new TreeSet<>(), loggersMap, Map.of()));

        MockMvc mvc = standaloneSetup(new LoggersController(providerOf(endpoint))).build();

        // Clearing the level
        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredLevel").doesNotExist())
                .andExpect(jsonPath("$.effectiveLevel").value("WARN"));

        // The GET list must reflect null configured and WARN effective.
        // The filter expression returns [null] for a null configuredLevel, so we use
        // a Hamcrest matcher rather than isEmpty() (which would reject [null]).
        mvc.perform(get("/bootui/api/loggers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggers[?(@.name=='com.example')].configuredLevel",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.loggers[?(@.name=='com.example')].effectiveLevel").value("WARN"));
    }

    // ── rejects invalid levels ────────────────────────────────────────────────

    @Test
    void postWithUnrecognisedLevel_returnsBadRequestAndErrorBody() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        MockMvc mvc = standaloneSetup(new LoggersController(providerOf(endpoint))).build();

        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"VERBOSE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    // ── missing endpoint + POST ───────────────────────────────────────────────

    @Test
    void postWhenEndpointMissing_propagatesError() {
        MockMvc mvc = standaloneSetup(new LoggersController(emptyProvider())).build();

        // When LoggersEndpoint is absent the controller throws IllegalStateException.
        // Spring MVC propagates it out of perform() as a wrapped servlet exception.
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"INFO\"}")));

        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(IllegalStateException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) EMPTY;
    }

    private static final ObjectProvider<Object> EMPTY = new ObjectProvider<>() {
        @Override
        public Object getObject(Object... args) { return null; }

        @Override
        public Object getIfAvailable() { return null; }

        @Override
        public Object getIfUnique() { return null; }

        @Override
        public Object getObject() { return null; }
    };
}
