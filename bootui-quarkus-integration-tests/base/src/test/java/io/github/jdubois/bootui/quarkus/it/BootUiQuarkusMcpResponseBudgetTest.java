package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.jdubois.bootui.conformance.McpResponseBudgetContract;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusMcpResponseBudgetTest.BudgetProfile.class)
class BootUiQuarkusMcpResponseBudgetTest {

    static final McpResponseBudgetContract CONTRACT = new McpResponseBudgetContract();

    public static class BudgetProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "bootui.mcp.enabled", "ON",
                    "bootui.mcp.max-response-bytes", "512");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(BoundedDispatcher.class, IndentingMapper.class);
        }
    }

    @Alternative
    @Singleton
    public static class IndentingMapper implements ObjectMapperCustomizer {
        @Override
        public void customize(ObjectMapper mapper) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
    }

    @Alternative
    @ApplicationScoped
    public static class BoundedDispatcher {
        @Produces
        @Singleton
        McpDispatcher dispatcher() {
            return new McpDispatcher(
                    List.of(CONTRACT.tool()),
                    List.of(),
                    new AllowAll(),
                    "test",
                    "test",
                    50,
                    1,
                    30_000,
                    (operation, failure) -> {
                        throw new AssertionError(operation, failure);
                    });
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Inject
    McpDispatcher dispatcher;

    @Inject
    ObjectMapper mapper;

    @Test
    void nativeJsonProviderFormattingAndCompactSseBothRespectTheBudget() throws Exception {
        assertThat(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT)).isTrue();
        CONTRACT.verify(baseUrl.getPort(), dispatcher, json -> {
            try {
                return mapper.writeValueAsBytes(mapper.readTree(json));
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private static final class AllowAll implements McpPanelPolicy {
        @Override
        public boolean isEnabled(String panelId) {
            return true;
        }

        @Override
        public String disabledReason(String panelId) {
            return "";
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return false;
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "";
        }
    }
}
