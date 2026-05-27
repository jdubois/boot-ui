package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ContextMappingsDescriptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class MappingsControllerTests {

    @Test
    void mappingsReturnsEmptyReportWhenActuatorUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new MappingsController(emptyProvider(), new BootUiProperties())).build();

        mvc.perform(get("/bootui/api/mappings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.mappings").isEmpty());
    }

    @Test
    void mappingsFlattensDispatcherMappingsIntoBootUiDto() throws Exception {
        ContextMappingsDescriptor context = mock(ContextMappingsDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(context.getMappings()).thenReturn(Map.of(
                "dispatcherServlets", Map.of("dispatcherServlet", List.of(Map.of(
                        "handler", "com.example.TestController#hello()",
                        "details", Map.of("requestMappingConditions", Map.of(
                                "patterns", List.of("/hello"),
                                "methods", List.of("GET"),
                                "produces", List.of("application/json"),
                                "consumes", List.of("application/json"))))))));

        ApplicationMappingsDescriptor descriptor = mock(ApplicationMappingsDescriptor.class,
                withSettings().mockMaker(MockMakers.INLINE));
        when(descriptor.getContexts()).thenReturn(Map.of("application", context));

        MappingsEndpoint endpoint = mock(MappingsEndpoint.class);
        when(endpoint.mappings()).thenReturn(descriptor);

        MockMvc mvc = standaloneSetup(new MappingsController(providerOf(endpoint), new BootUiProperties())).build();

        mvc.perform(get("/bootui/api/mappings").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.mappings[0].method").value("GET"))
                .andExpect(jsonPath("$.mappings[0].pattern").value("/hello"))
                .andExpect(jsonPath("$.mappings[0].handler").value("com.example.TestController#hello()"))
                .andExpect(jsonPath("$.mappings[0].produces").value("application/json"))
                .andExpect(jsonPath("$.mappings[0].consumes").value("application/json"));
    }

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
}
