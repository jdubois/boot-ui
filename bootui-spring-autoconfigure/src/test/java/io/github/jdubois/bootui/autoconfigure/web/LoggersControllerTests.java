package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.logging.SpringLoggerProvider;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint.SingleLoggerLevelsDescriptor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link LoggersController} mutation behavior.
 *
 * <p>Exercises {@code POST /bootui/api/loggers/{name}} for setting, clearing,
 * and rejecting invalid log levels.</p>
 */
class LoggersControllerTests {

    /** Builds the full Spring delegation chain over a mocked Actuator endpoint. */
    private static LoggersController controllerFor(LoggersEndpoint endpoint) {
        BootUiSelfDataFilter self = BootUiSelfDataFilter.defaults();
        LoggersService service = new LoggersService(
                new SpringLoggerProvider(() -> endpoint), self::shouldIncludeLogger, self::isBootUiLoggerName);
        return new LoggersController(service);
    }

    @Test
    void postUpdatesLogLevelAndReturnsRefreshedDto() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        when(endpoint.loggerLevels("com.example"))
                .thenReturn(new SingleLoggerLevelsDescriptor(
                        new LoggerConfiguration("com.example", LogLevel.DEBUG, LogLevel.DEBUG)));

        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"DEBUG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("com.example"))
                .andExpect(jsonPath("$.configuredLevel").value("DEBUG"))
                .andExpect(jsonPath("$.effectiveLevel").value("DEBUG"));

        verify(endpoint, times(1)).configureLogLevel(eq("com.example"), eq(LogLevel.DEBUG));
    }

    @Test
    void postWithBlankLevelClearsConfiguredLevel() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        when(endpoint.loggerLevels("com.example"))
                .thenReturn(
                        new SingleLoggerLevelsDescriptor(new LoggerConfiguration("com.example", null, LogLevel.INFO)));

        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("com.example"))
                .andExpect(jsonPath("$.configuredLevel").doesNotExist())
                .andExpect(jsonPath("$.effectiveLevel").value("INFO"));

        verify(endpoint, times(1)).configureLogLevel(eq("com.example"), eq((LogLevel) null));
    }

    @Test
    void postWithInvalidLevelReturnsBadRequest() throws Exception {
        LoggersEndpoint endpoint = mock(LoggersEndpoint.class);
        MockMvc mvc = standaloneSetup(controllerFor(endpoint)).build();

        mvc.perform(post("/bootui/api/loggers/com.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"NOT_A_LEVEL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(endpoint, never()).configureLogLevel(eq("com.example"), org.mockito.ArgumentMatchers.any());
    }
}
