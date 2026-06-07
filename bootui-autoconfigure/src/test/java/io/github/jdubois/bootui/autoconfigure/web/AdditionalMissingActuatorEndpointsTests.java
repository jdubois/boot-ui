package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Extends missing-actuator/framework endpoint coverage beyond what
 * {@code MissingActuatorEndpointsTests} already covers.
 *
 * <h2>Already covered in MissingActuatorEndpointsTests</h2>
 * <ul>
 *   <li>BeansController ({@code BeansEndpoint} absent)</li>
 *   <li>ConditionsController ({@code ConditionsReportEndpoint} absent)</li>
 *   <li>HealthController ({@code HealthEndpoint} absent)</li>
 *   <li>LoggersController ({@code LoggersEndpoint} absent)</li>
 *   <li>MetricsController ({@code MeterRegistry} absent)</li>
 *   <li>MappingsController ({@code MappingsEndpoint} absent)</li>
 *   <li>StartupController ({@code StartupEndpoint} absent)</li>
 *   <li>ScheduledController ({@code ScheduledTaskHolder} absent)</li>
 *   <li>SpringSecurityController ({@code FilterChainProxy} absent)</li>
 * </ul>
 *
 * <h2>Skipped (no optional-dependency branch)</h2>
 * <ul>
 *   <li>HttpProbeController — always available; uses {@link org.springframework.core.env.Environment} directly</li>
 *   <li>LogTailController — uses {@link BootUiLogAppender} directly; no optional ObjectProvider</li>
 *   <li>ProfileController — uses {@link org.springframework.core.env.ConfigurableEnvironment} directly</li>
 *   <li>LiveMemoryController / JvmTuningController — read JVM MXBeans directly; no optional dependency</li>
 *   <li>OverviewController — uses Environment and BootUiActivation directly</li>
 *   <li>DevServicesController — uses {@link org.springframework.context.ConfigurableApplicationContext} directly</li>
 *   <li>DevToolsController — DevToolsBridge is always wired</li>
 * </ul>
 *
 * <h2>Added here</h2>
 * <ul>
 *   <li>DataController — when {@code ListableBeanFactory} is absent, must return a stable
 *       empty {@code RepositoriesReport} rather than throwing</li>
 * </ul>
 */
class AdditionalMissingActuatorEndpointsTests {

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

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) EMPTY;
    }

    /**
     * When no {@link ListableBeanFactory} is available (e.g., DataController
     * is created outside a full application context), {@code GET /bootui/api/data/repositories}
     * must return a stable empty report rather than throwing.
     *
     * <p>{@code springDataPresent} stays {@code true} because the controller class itself is
     * loaded (its {@code @ConditionalOnClass} guard passed) — only the factory bean is absent.</p>
     */
    @Test
    void dataControllerReturnsEmptyRepositoriesWhenBeanFactoryMissing() throws Exception {
        MockMvc mvc = standaloneSetup(new DataController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/data/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springDataPresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.repositories").isArray())
                .andExpect(jsonPath("$.repositories").isEmpty());
    }

    /**
     * When no factory is available, looking up a specific repository by name must
     * return {@code 404 Not Found} rather than throwing.
     */
    @Test
    void dataControllerReturnsNotFoundForSpecificRepositoryWhenBeanFactoryMissing() throws Exception {
        MockMvc mvc = standaloneSetup(new DataController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/data/repositories/MyRepository")).andExpect(status().isNotFound());
    }
}
