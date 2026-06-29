package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.logging.SpringLoggerProvider;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
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
 *
 * <p>The controller is now a thin delegate over the engine {@link LoggersService}; these tests build
 * the full Spring chain ({@code SpringLoggerProvider} over the mocked Actuator endpoint, wired into the
 * engine service) so the end-to-end behavior stays covered.</p>
 */
class LoggersControllerMutationTests {

    /** Builds the full Spring delegation chain over an Actuator endpoint (or {@code null} when absent). */
    private static LoggersController controllerFor(LoggersEndpoint endpoint) {
        BootUiSelfDataFilter self = BootUiSelfDataFilter.defaults();
        LoggersService service = new LoggersService(
                new SpringLoggerProvider(() -> endpoint), self::shouldIncludeLogger, self::isBootUiLoggerName);
        return new LoggersController(service);
    }

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
        loggersMap.put(
                "com.example",
                new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example", LogLevel.DEBUG, LogLevel.DEBUG)));
        TreeSet<LogLevel> levels = new TreeSet<>();
        levels.addAll(java.util.List.of(
                LogLevel.TRACE,
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR,
                LogLevel.FATAL,
                LogLevel.OFF));
        when(endpoint.loggers()).thenReturn(new LoggersDescriptor(levels, loggersMap, Map.of()));

        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

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
                .andExpect(jsonPath("$.loggers[?(@.name=='com.example')].configuredLevel")
                        .value("DEBUG"));

        verify(endpoint, times(1)).configureLogLevel(eq("com.example"), eq(LogLevel.DEBUG));
    }

    @Test
    void getAllSupportsServerSideFilteringAndPaging() throws Exception {
        Map<String, LoggerLevelsDescriptor> loggersMap = new LinkedHashMap<>();
        loggersMap.put(
                "com.example.Alpha",
                new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example.Alpha", LogLevel.DEBUG, LogLevel.DEBUG)));
        loggersMap.put(
                "com.example.Beta",
                new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example.Beta", LogLevel.INFO, LogLevel.INFO)));
        loggersMap.put(
                "org.springframework",
                new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("org.springframework", LogLevel.WARN, LogLevel.WARN)));
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        when(endpoint.loggers()).thenReturn(new LoggersDescriptor(new TreeSet<>(), loggersMap, Map.of()));

        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

        mvc.perform(get("/bootui/api/loggers")
                        .param("q", "com.example")
                        .param("offset", "1")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggers.length()").value(1))
                .andExpect(jsonPath("$.loggers[0].name").value("com.example.Beta"))
                .andExpect(jsonPath("$.page.total").value(3))
                .andExpect(jsonPath("$.page.matched").value(2))
                .andExpect(jsonPath("$.page.offset").value(1))
                .andExpect(jsonPath("$.page.returned").value(1));
    }

    @Test
    void getAllHidesBootUiInternalLoggersButKeepsSampleAppLoggers() throws Exception {
        Map<String, LoggerLevelsDescriptor> loggersMap = new LinkedHashMap<>();
        loggersMap.put(
                "io.github.jdubois.bootui.autoconfigure.web.BeansController",
                new SingleLoggerLevelsDescriptor(new LoggerConfiguration(
                        "io.github.jdubois.bootui.autoconfigure.web.BeansController", LogLevel.DEBUG, LogLevel.DEBUG)));
        loggersMap.put(
                "io.github.jdubois.bootui.sample.SampleApplication",
                new SingleLoggerLevelsDescriptor(new LoggerConfiguration(
                        "io.github.jdubois.bootui.sample.SampleApplication", LogLevel.INFO, LogLevel.INFO)));
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        when(endpoint.loggers()).thenReturn(new LoggersDescriptor(new TreeSet<>(), loggersMap, Map.of()));

        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

        mvc.perform(get("/bootui/api/loggers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggers.length()").value(1))
                .andExpect(jsonPath("$.loggers[0].name").value("io.github.jdubois.bootui.sample.SampleApplication"));
    }

    @Test
    void clearLevel_withNullLevelJson_callsConfigureWithNullLevel() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        // After clearing, the logger has no configured level but inherits INFO
        when(endpoint.loggerLevels("com.example"))
                .thenReturn(new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example", (LogLevel) null, LogLevel.INFO)));

        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("com.example"))
                .andExpect(jsonPath("$.configuredLevel").doesNotExist())
                .andExpect(jsonPath("$.effectiveLevel").value("INFO"));

        verify(endpoint, times(1)).configureLogLevel(eq("com.example"), eq((LogLevel) null));
    }

    @Test
    void clearLevel_thenGetAll_showsNullConfiguredAndInheritedEffectiveLevel() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);

        // POST clear (blank level): returns the logger without a configured level
        when(endpoint.loggerLevels("com.example"))
                .thenReturn(new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example", (LogLevel) null, LogLevel.WARN)));

        // GET all: the descriptor also shows null configured, WARN effective
        Map<String, LoggerLevelsDescriptor> loggersMap = new LinkedHashMap<>();
        loggersMap.put(
                "com.example",
                new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example", (LogLevel) null, LogLevel.WARN)));
        when(endpoint.loggers()).thenReturn(new LoggersDescriptor(new TreeSet<>(), loggersMap, Map.of()));

        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

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
                .andExpect(jsonPath(
                        "$.loggers[?(@.name=='com.example')].configuredLevel",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.loggers[?(@.name=='com.example')].effectiveLevel")
                        .value("WARN"));
    }

    @Test
    void postWithUnrecognisedLevel_returnsBadRequestAndErrorBody() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"VERBOSE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void postToBootUiOwnLogger_isRejectedAsBadRequest() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

        // The write guard rejects BootUI's own loggers before touching the backend, regardless of the
        // read-side self-data preference, so a level change can never fall through to a real mutation.
        mvc.perform(post("/bootui/api/loggers/io.github.jdubois.bootui.autoconfigure.web.LoggersController")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"DEBUG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());

        verify(endpoint, never()).configureLogLevel(any(), any());
    }

    @Test
    void postWhenEndpointMissing_propagatesError() {
        MockMvc mvc = standaloneSetup(controllerFor(null)).build();

        // When the logging backend is absent the engine service throws IllegalStateException.
        // Spring MVC propagates it out of perform() as a wrapped servlet exception.
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"INFO\"}")));

        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(IllegalStateException.class);
    }
}
