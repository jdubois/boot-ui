package io.github.jdubois.bootui.autoconfigure.mappings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.ActuatorMappingsController;
import io.github.jdubois.bootui.autoconfigure.web.MappingsController;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ContextMappingsDescriptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDescription;
import org.springframework.http.HttpStatus;

/**
 * Real-context wiring test for the Mappings backend. Unlike {@code MappingsControllerTests} (which mocks
 * the engine service) and {@code SpringMappingProviderTests} (which constructs the provider directly),
 * this boots {@link BootUiAutoConfiguration} so it exercises the full chain the application uses:
 * the {@code @ConditionalOnClass}-gated {@code MappingsBackendConfiguration} -&gt;
 * {@link SpringMappingProvider} -&gt; {@code bootUiMappingsService} factory -&gt; {@link MappingsService}
 * -&gt; the neutral {@link MappingsController}. It closes the gap a wrong import, an inactive nested
 * config, a wrong SPI type or a miswired factory would leave open.
 */
class MappingsBackendWiringTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void wiresRealProviderServiceAndControllerAndServesFilteredSortedPagedFlatMappings() {
        runner.withBean(
                        MappingsEndpoint.class,
                        () -> endpointReturning(
                                mapping("/zebra", "com.example.ZebraController#z"),
                                mapping("/alpha", "com.example.AlphaController#a"),
                                mapping(
                                        "/bootui/api/beans",
                                        "io.github.jdubois.bootui.autoconfigure.web.BeansController#b")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SpringMappingProvider.class);
                    assertThat(context).hasSingleBean(MappingsService.class);
                    assertThat(context).hasSingleBean(MappingsController.class);
                    assertThat(context).hasSingleBean(ActuatorMappingsController.class);

                    MappingsController controller = context.getBean(MappingsController.class);

                    // Full unpaged read: BootUI's own endpoint filtered out, the rest sorted by pattern.
                    MappingsReport all = controller.flatMappings(null, null, null);
                    assertThat(all.total()).isEqualTo(2);
                    assertThat(all.mappings()).extracting(MappingDto::pattern).containsExactly("/alpha", "/zebra");

                    // Query narrows by substring across the flattened fields.
                    assertThat(controller.flatMappings("zebra", null, null).mappings())
                            .extracting(MappingDto::pattern)
                            .containsExactly("/zebra");

                    // Paging limits the returned window while total still reflects the full set.
                    MappingsReport firstPage = controller.flatMappings(null, 0, 1);
                    assertThat(firstPage.total()).isEqualTo(2);
                    assertThat(firstPage.mappings())
                            .extracting(MappingDto::pattern)
                            .containsExactly("/alpha");
                    assertThat(firstPage.page().hasMore()).isTrue();
                });
    }

    @Test
    void rawDescriptorEndpointReturnsNoContentWhenEndpointBeanAbsent() {
        // MappingsEndpoint class present (test classpath) but no bean: the gated ActuatorMappingsController
        // is wired and returns 204, and the neutral MappingsController still serves an empty /flat report.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ActuatorMappingsController.class);
            assertThat(context.getBean(ActuatorMappingsController.class)
                            .mappings()
                            .getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(context.getBean(MappingsController.class)
                            .flatMappings(null, null, null)
                            .total())
                    .isZero();
        });
    }

    private static DispatcherServletMappingDescription mapping(String predicate, String handler) {
        DispatcherServletMappingDescription description =
                mock(DispatcherServletMappingDescription.class, withSettings().mockMaker(MockMakers.INLINE));
        when(description.getPredicate()).thenReturn(predicate);
        when(description.getHandler()).thenReturn(handler);
        when(description.getDetails()).thenReturn(null);
        return description;
    }

    private static MappingsEndpoint endpointReturning(DispatcherServletMappingDescription... descriptions) {
        ContextMappingsDescriptor context =
                mock(ContextMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(context.getMappings())
                .thenReturn(Map.of("dispatcherServlets", Map.of("dispatcherServlet", List.of(descriptions))));
        ApplicationMappingsDescriptor descriptor =
                mock(ApplicationMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getContexts()).thenReturn(Map.of("application", context));
        MappingsEndpoint endpoint = mock(MappingsEndpoint.class, withSettings().mockMaker(MockMakers.INLINE));
        when(endpoint.mappings()).thenReturn(descriptor);
        return endpoint;
    }
}
