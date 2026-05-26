package io.github.bootui.autoconfigure.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.bootui.autoconfigure.BootUiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

class DevServicesControllerTests {

    @Test
    void listReturnsEmptyReportWhenNoDevServicesArePresent() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

        mvc.perform(get("/bootui/api/dev-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.services").isEmpty());

        context.close();
    }

    @Test
    void listReturnsMaskedConnectionDetails() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresConnectionDetails", SampleConnectionDetails.class);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

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
    void logsReturnsCappedTailForBeanBackedService() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getDevServices().setLogTailBytes(12);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, properties)).build();

        mvc.perform(get("/bootui/api/dev-services/bean:postgresTestcontainer/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.logs").value(containsString("3456789-end")));

        context.close();
    }

    @Test
    void restartIsDisabledByDefault() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.refresh();
        MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

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
        MockMvc mvc = standaloneSetup(new DevServicesController(context, properties)).build();

        mvc.perform(post("/bootui/api/dev-services/bean:postgresTestcontainer/restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("restarted"));

        FakeTestcontainer container = context.getBean(FakeTestcontainer.class);
        org.assertj.core.api.Assertions.assertThat(container.restartCalls()).isEqualTo("stop,start");
        context.close();
    }

    static class SampleConnectionDetails implements ConnectionDetails {

        public String getJdbcUrl() {
            return "jdbc:postgresql://dbuser:secret@localhost:5432/app";
        }

        public String getPassword() {
            return "secret";
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
}
