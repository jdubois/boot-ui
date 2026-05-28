package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ContextMappingsDescriptor;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDescription;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link MappingsController}.
 *
 * <p>Verifies that {@code GET /bootui/api/mappings} returns {@code 204 No Content}
 * when the {@link MappingsEndpoint} bean is absent and {@code 200 OK} with JSON
 * when the endpoint is present. {@link ApplicationMappingsDescriptor} is a
 * {@code final} class and is mocked with {@link MockMakers#INLINE}.</p>
 */
class MappingsControllerTests {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MappingsEndpoint> providerOf(MappingsEndpoint endpoint) {
        ObjectProvider<MappingsEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(endpoint);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MappingsEndpoint> emptyProvider() {
        ObjectProvider<MappingsEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void mappingsReturnsNoContentWhenActuatorUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new MappingsController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/mappings")).andExpect(status().isNoContent());
    }

    @Test
    void mappingsReturnsOkWithDescriptorWhenActuatorPresent() throws Exception {
        ApplicationMappingsDescriptor descriptor =
                mock(ApplicationMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getContexts()).thenReturn(Map.of());

        MappingsEndpoint endpoint = mock(MappingsEndpoint.class);
        when(endpoint.mappings()).thenReturn(descriptor);

        MockMvc mvc =
                standaloneSetup(new MappingsController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/mappings").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void flatMappingsReturnsStablePagedDto() throws Exception {
        DispatcherServletMappingDescription alpha = mock(DispatcherServletMappingDescription.class);
        when(alpha.getPredicate()).thenReturn("/alpha");
        when(alpha.getHandler()).thenReturn("org.example.AlphaController#alpha");

        DispatcherServletMappingDescription beta = mock(DispatcherServletMappingDescription.class);
        when(beta.getPredicate()).thenReturn("/beta");
        when(beta.getHandler()).thenReturn("org.example.BetaController#beta");

        ContextMappingsDescriptor context =
                mock(ContextMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(context.getMappings())
                .thenReturn(Map.of("dispatcherServlets", Map.of("dispatcherServlet", List.of(alpha, beta))));

        ApplicationMappingsDescriptor descriptor =
                mock(ApplicationMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getContexts()).thenReturn(Map.of("application", context));

        MappingsEndpoint endpoint = mock(MappingsEndpoint.class);
        when(endpoint.mappings()).thenReturn(descriptor);

        MockMvc mvc =
                standaloneSetup(new MappingsController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/mappings/flat")
                        .param("q", "alpha")
                        .param("limit", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.mappings.length()").value(1))
                .andExpect(jsonPath("$.mappings[0].method").value("ANY"))
                .andExpect(jsonPath("$.mappings[0].pattern").value("/alpha"))
                .andExpect(jsonPath("$.mappings[0].handler").value("org.example.AlphaController#alpha"))
                .andExpect(jsonPath("$.page.total").value(2))
                .andExpect(jsonPath("$.page.matched").value(1))
                .andExpect(jsonPath("$.page.returned").value(1));
    }
}
