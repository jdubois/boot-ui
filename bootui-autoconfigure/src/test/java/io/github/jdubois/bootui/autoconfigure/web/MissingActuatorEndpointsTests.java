package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.startup.StartupEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that every BootUI controller backed by an optional Actuator or
 * framework endpoint returns a stable empty DTO (and never throws) when the
 * underlying endpoint bean is not present in the application context.
 */
class MissingActuatorEndpointsTests {

    private static final ObjectProvider<Object> EMPTY = new ObjectProvider<>() {
        @Override
        public Object getObject(Object... args) {
            return null;
        }

        @Override
        public Object getIfAvailable() {
            return null;
        }

        @Override
        public Object getIfUnique() {
            return null;
        }

        @Override
        public Object getObject() {
            return null;
        }
    };
    // Reference unused security types so that the test fails fast at compile
    // time if the optional Spring Security classpath changes shape.
    @SuppressWarnings("unused")
    private static final Class<?>[] SECURITY_TYPES = {
        FilterChainProxy.class, AuthenticationProvider.class, UserDetailsService.class
    };

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) EMPTY;
    }

    @Test
    void beansControllerReturnsEmptyListWhenEndpointMissing() throws Exception {
        ObjectProvider<BeansEndpoint> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new BeansController(provider)).build();

        mvc.perform(get("/bootui/api/beans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.beans").isArray())
                .andExpect(jsonPath("$.beans").isEmpty());
    }

    @Test
    void conditionsControllerReturnsEmptyReportWhenEndpointMissing() throws Exception {
        ObjectProvider<ConditionsReportEndpoint> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new ConditionsController(provider)).build();

        mvc.perform(get("/bootui/api/conditions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positiveMatches").isEmpty())
                .andExpect(jsonPath("$.negativeMatches").isEmpty())
                .andExpect(jsonPath("$.unconditionalClasses").isEmpty())
                .andExpect(jsonPath("$.exclusions").isEmpty());
    }

    @Test
    void healthControllerReturnsUnknownRootWhenEndpointMissing() throws Exception {
        ObjectProvider<HealthEndpoint> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new HealthController(provider)).build();

        mvc.perform(get("/bootui/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("application"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.components").isEmpty());
    }

    @Test
    void loggersControllerReturnsEmptyReportWhenEndpointMissing() throws Exception {
        ObjectProvider<LoggersEndpoint> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new LoggersController(provider)).build();

        mvc.perform(get("/bootui/api/loggers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableLevels").isEmpty())
                .andExpect(jsonPath("$.loggers").isEmpty());
    }

    @Test
    void metricsControllerReturnsEmptyReportWhenRegistryMissing() throws Exception {
        ObjectProvider<MeterRegistry> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsAvailable").value(false))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.meters").isEmpty());
    }

    @Test
    void mappingsControllerReturnsNoContentWhenEndpointMissing() throws Exception {
        ObjectProvider<MappingsEndpoint> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new MappingsController(provider)).build();

        mvc.perform(get("/bootui/api/mappings")).andExpect(status().isNoContent());
    }

    @Test
    void startupControllerReturnsEmptyReportWhenEndpointMissing() throws Exception {
        ObjectProvider<StartupEndpoint> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new StartupController(provider)).build();

        mvc.perform(get("/bootui/api/startup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps").isEmpty());
    }

    @Test
    void scheduledControllerReturnsAbsentSchedulingReportWhenHolderMissing() throws Exception {
        ObjectProvider<ScheduledTaskHolder> provider = emptyProvider();
        MockMvc mvc = standaloneSetup(new ScheduledController(provider)).build();

        mvc.perform(get("/bootui/api/scheduled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(false))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void securityControllerReturnsAbsentReportWhenFilterChainProxyMissing() throws Exception {
        SecurityController controller = new SecurityController(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                new MockEnvironment(),
                new BootUiProperties());
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/security"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springSecurityPresent").value(false))
                .andExpect(jsonPath("$.chains").isEmpty())
                .andExpect(jsonPath("$.auth").doesNotExist());
    }
}
