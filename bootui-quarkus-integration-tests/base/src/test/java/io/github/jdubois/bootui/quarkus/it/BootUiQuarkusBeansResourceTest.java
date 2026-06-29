package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Beans panel ({@code BeansResource} over the engine
 * {@code BeansService} backed by {@code QuarkusBeanProvider} / the Arc CDI container). The neutral
 * sort/filter/page logic is unit-tested in the engine {@code BeansServiceTests}; this test pins the
 * Quarkus-specific behavior: the live Arc container is enumerated, the application's own bean is listed,
 * and BootUI's own beans are filtered out (so the Beans panel never exposes the extension's internals).
 */
@QuarkusTest
class BootUiQuarkusBeansResourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void listsApplicationBeansAndHidesBootUisOwnBeans() {
        Response response = probe().get("/bootui/api/beans");
        assertThat(response.status()).as("GET /bootui/api/beans status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/beans content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("beans").isArray()).as("$.beans must be an array").isTrue();

        List<String> types = new ArrayList<>();
        body.path("beans").forEach(bean -> types.add(bean.path("type").asText()));

        assertThat(types)
                .as("the live Arc container must surface the application's own bean")
                .contains("org.acme.beansdemo.BeansDemoBean");

        assertThat(types)
                .as("BootUI's own beans must be filtered out of the Beans panel")
                .noneMatch(type -> type.startsWith("io.github.jdubois.bootui"));
    }
}
