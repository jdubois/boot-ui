package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.core.dto.DevServiceDto;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

class DevServicesControllerTests {

    @Test
    void listReturnsEmptyReportWhenNoDevServicesArePresent() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.services").isEmpty())
                .andExpect(jsonPath("$.warnings").isEmpty());

        context.close();
    }

    @Test
    void listReturnsMaskedConnectionDetails() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresConnectionDetails", SampleConnectionDetails.class);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.services[0].id").value("connection:postgresConnectionDetails"))
                .andExpect(jsonPath("$.services[0].type").value("PostgreSQL"))
                .andExpect(jsonPath("$.services[0].connectionDetails.jdbcUrl")
                        .value("jdbc:postgresql://******@localhost:5432/app"))
                .andExpect(jsonPath("$.services[0].connectionDetails.password").value("******"));

        context.close();
    }

    @Test
    void listExposesConnectionDetailsUnderFullExposure() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.FULL);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresConnectionDetails", SampleConnectionDetails.class);
        context.refresh();
        MockMvc mvc =
                standaloneSetup(new DevServicesController(context, properties)).build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].connectionDetails.jdbcUrl")
                        .value("jdbc:postgresql://dbuser:secret@localhost:5432/app"))
                .andExpect(jsonPath("$.services[0].connectionDetails.password").value("secret"));

        context.close();
    }

    @Test
    void listSkipsLazyConnectionDetailsWithoutInitializingBean() throws Exception {
        LazyConnectionDetails.initialized.set(false);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(
                "lazyConnectionDetails", LazyConnectionDetails.class, definition -> definition.setLazyInit(true));
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.warnings[0]").value(containsString("lazyConnectionDetails")))
                .andExpect(jsonPath("$.warnings[0]").value(containsString("lazy bean")));

        assertThat(LazyConnectionDetails.initialized).isFalse();
        context.close();
    }

    @Test
    void logsReturnsCappedTailForBeanBackedService() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getDevServices().setLogTailBytes(12);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.refresh();
        MockMvc mvc =
                standaloneSetup(new DevServicesController(context, properties)).build();

        mvc.perform(get("/bootui/api/dev-services/bean:postgresTestcontainer/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.logs").value(containsString("3456789-end")));

        context.close();
    }

    @Test
    void logsSupportsTestcontainersVarargsLogMethod() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("redisTestcontainer", FakeVarargsLogTestcontainer.class);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services/bean:redisTestcontainer/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").value("Redis container started"));

        context.close();
    }

    @Test
    void logsDoNotInitializeLazyBeanBackedService() throws Exception {
        LazyTestcontainer.initialized.set(false);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(
                "lazyPostgresTestcontainer", LazyTestcontainer.class, definition -> definition.setLazyInit(true));
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services/bean:lazyPostgresTestcontainer/logs"))
                .andExpect(status().isConflict());

        assertThat(LazyTestcontainer.initialized).isFalse();
        context.close();
    }

    @Test
    void restartIsDisabledByDefault() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(post("/bootui/api/dev-services/bean:postgresTestcontainer/restart"))
                .andExpect(status().isConflict());

        context.close();
    }

    @Test
    void restartCallsStopThenStartWhenExplicitlyEnabled() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getDevServices().setRestartEnabled(true);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.refresh();
        MockMvc mvc =
                standaloneSetup(new DevServicesController(context, properties)).build();

        mvc.perform(post("/bootui/api/dev-services/bean:postgresTestcontainer/restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("restarted"));

        FakeTestcontainer container = context.getBean(FakeTestcontainer.class);
        assertThat(container.restartCalls()).isEqualTo("stop,start");
        context.close();
    }

    @Test
    void listSkipsPrototypeScopedTestcontainerWithWarning() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(
                "protoTestcontainer",
                FakeTestcontainer.class,
                definition -> definition.setScope(BeanDefinition.SCOPE_PROTOTYPE));
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.warnings[0]").value(containsString("protoTestcontainer")))
                .andExpect(jsonPath("$.warnings[0]").value(containsString("prototype bean")));

        context.close();
    }

    @Test
    void listSilentlySkipsAbstractBeanDefinition() throws Exception {
        // Abstract bean definitions are excluded from Testcontainers discovery because
        // safeGetType returns null for them (Spring cannot predict the type without
        // instantiation). They are silently ignored rather than generating a warning.
        GenericApplicationContext context = new GenericApplicationContext();
        RootBeanDefinition def = new RootBeanDefinition(FakeTestcontainer.class);
        def.setAbstract(true);
        context.registerBeanDefinition("abstractTestcontainer", def);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.warnings").isEmpty());

        context.close();
    }

    @Test
    void listShowsStoppedTestcontainerStatusAsSTOPPED() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("stoppedPostgresTestcontainer", StoppedFakeTestcontainer.class);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.services[0].id").value("bean:stoppedPostgresTestcontainer"))
                .andExpect(jsonPath("$.services[0].status").value("STOPPED"));

        context.close();
    }

    @Test
    void logsReturnsEmptyStringWhenGetLogsReturnsNull() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("nullLogsTestcontainer", NullLogsFakeTestcontainer.class);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/dev-services/bean:nullLogsTestcontainer/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").value(""))
                .andExpect(jsonPath("$.truncated").value(false));

        context.close();
    }

    @Test
    void restartReturns500WhenStopThrows() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getDevServices().setRestartEnabled(true);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("failingTestcontainer", ThrowingStopFakeTestcontainer.class);
        context.refresh();
        MockMvc mvc =
                standaloneSetup(new DevServicesController(context, properties)).build();

        mvc.perform(post("/bootui/api/dev-services/bean:failingTestcontainer/restart"))
                .andExpect(status().isInternalServerError());

        context.close();
    }

    @Test
    void listHidesConnectionDetailsValuesUnderMetadataOnlyExposure() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.METADATA_ONLY);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresConnectionDetails", SampleConnectionDetails.class);
        context.refresh();
        MockMvc mvc =
                standaloneSetup(new DevServicesController(context, properties)).build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.services[0].connectionDetails.jdbcUrl").value((Object) null))
                .andExpect(jsonPath("$.services[0].connectionDetails.password").value((Object) null));

        context.close();
    }

    @Test
    void dockerComposeDuplicateNamesReceiveUniqueIds() throws Exception {
        DevServicesService service =
                new DevServicesService(new GenericApplicationContext(), new BootUiProperties());
        Method method =
                DevServicesService.class.getDeclaredMethod("dockerComposeDto", Object.class, Map.class, List.class);
        method.setAccessible(true);
        Map<String, Integer> ids = new HashMap<>();
        List<String> warnings = new ArrayList<>();

        DevServiceDto first = (DevServiceDto)
                method.invoke(service, new FakeComposeService("postgres", "postgres:16"), ids, warnings);
        DevServiceDto second = (DevServiceDto)
                method.invoke(service, new FakeComposeService("postgres", "postgres:17"), ids, warnings);
        DevServiceDto blank = (DevServiceDto) method.invoke(service, new FakeComposeService("", ""), ids, warnings);

        assertThat(first.id()).isEqualTo("compose:postgres");
        assertThat(second.id()).isEqualTo("compose:postgres-2");
        assertThat(blank.id()).isEqualTo("compose:service");
        assertThat(warnings)
                .anySatisfy(warning -> assertThat(warning).contains("duplicate service id 'compose:postgres'"));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).contains("without a name"));
    }

    static class SampleConnectionDetails implements ConnectionDetails {

        public String getJdbcUrl() {
            return "jdbc:postgresql://dbuser:secret@localhost:5432/app";
        }

        public String getPassword() {
            return "secret";
        }
    }

    static class LazyConnectionDetails implements ConnectionDetails {

        static final AtomicBoolean initialized = new AtomicBoolean();

        LazyConnectionDetails() {
            initialized.set(true);
        }

        public String getJdbcUrl() {
            return "jdbc:postgresql://localhost:5432/app";
        }
    }

    static class FakeTestcontainer {

        private final StringBuilder restartCalls = new StringBuilder();

        public String getDockerImageName() {
            return "postgres:16";
        }

        public String getContainerName() {
            return "postgres";
        }

        public boolean isRunning() {
            return true;
        }

        public String getLogs() {
            return "x".repeat(1100) + "3456789-end";
        }

        public void stop() {
            restartCalls.append("stop");
        }

        public void start() {
            if (!restartCalls.isEmpty()) {
                restartCalls.append(",");
            }
            restartCalls.append("start");
        }

        String restartCalls() {
            return restartCalls.toString();
        }
    }

    static class FakeVarargsLogTestcontainer {

        public String getDockerImageName() {
            return "redis:7";
        }

        public String getContainerName() {
            return "redis";
        }

        public boolean isRunning() {
            return true;
        }

        public String getLogs(String... outputTypes) {
            return "Redis container started";
        }
    }

    static class LazyTestcontainer {

        static final AtomicBoolean initialized = new AtomicBoolean();

        LazyTestcontainer() {
            initialized.set(true);
        }

        public String getDockerImageName() {
            return "postgres:16";
        }

        public boolean isRunning() {
            return true;
        }

        public String getLogs() {
            return "lazy logs";
        }
    }

    static class StoppedFakeTestcontainer extends FakeTestcontainer {

        @Override
        public boolean isRunning() {
            return false;
        }
    }

    static class NullLogsFakeTestcontainer {

        public String getDockerImageName() {
            return "postgres:16";
        }

        public String getContainerName() {
            return "postgres";
        }

        public boolean isRunning() {
            return true;
        }

        public String getLogs() {
            return null;
        }
    }

    static class ThrowingStopFakeTestcontainer extends FakeTestcontainer {

        @Override
        public void stop() {
            throw new RuntimeException("Docker daemon unreachable");
        }
    }

    record FakeComposeService(String name, String image) {

        public String host() {
            return "localhost";
        }

        public Map<String, String> labels() {
            return Map.of();
        }

        public FakeComposePorts ports() {
            return new FakeComposePorts();
        }
    }

    static class FakeComposePorts {

        public List<Integer> getAll() {
            return List.of(15432);
        }
    }
}
