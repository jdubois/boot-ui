package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for the gated {@link ActuatorMappingsController}, the raw {@code GET /bootui/api/mappings}
 * descriptor passthrough.
 *
 * <p>Verifies {@code 204 No Content} when the {@link MappingsEndpoint} bean is absent (Actuator present
 * but the endpoint not exposed) and {@code 200 OK} with JSON when it is present.
 * {@link ApplicationMappingsDescriptor} is a {@code final} class and is mocked with
 * {@link MockMakers#INLINE}.</p>
 */
class ActuatorMappingsControllerTests {

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
    void returnsNoContentWhenEndpointBeanAbsent() throws Exception {
        MockMvc mvc =
                standaloneSetup(new ActuatorMappingsController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/mappings")).andExpect(status().isNoContent());
    }

    @Test
    void returnsOkWithDescriptorWhenEndpointPresent() throws Exception {
        ApplicationMappingsDescriptor descriptor =
                mock(ApplicationMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getContexts()).thenReturn(Map.of());

        MappingsEndpoint endpoint = mock(MappingsEndpoint.class);
        when(endpoint.mappings()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new ActuatorMappingsController(providerOf(endpoint)))
                .build();

        mvc.perform(get("/bootui/api/mappings").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
