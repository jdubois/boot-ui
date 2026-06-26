package io.github.jdubois.bootui.autoconfigure.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.ValueExposure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;

class ExceptionsControllerTests {

    @Test
    void reportsUnavailableWhenCaptureIsDisabled() throws Exception {
        MockMvc mvc = buildMvc(null, new BootUiProperties());

        mvc.perform(get("/bootui/api/exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("Exception capture is disabled"))
                .andExpect(jsonPath("$.maxGroups").value(100))
                .andExpect(jsonPath("$.groups").isEmpty());
    }

    @Test
    void listsGroupedExceptionsWithMaskedMessages() throws Exception {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(
                new IllegalStateException("login failed token=abcdef123"), "main", "GET", "/api/orders", "X#y", "web");
        MockMvc mvc = buildMvc(store, new BootUiProperties());

        mvc.perform(get("/bootui/api/exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.totalExceptions").value(1))
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].exceptionClassName").value("java.lang.IllegalStateException"))
                .andExpect(jsonPath("$.groups[0].message").value("login failed token=******"))
                .andExpect(jsonPath("$.groups[0].lastRequestPath").value("/api/orders"))
                .andExpect(jsonPath("$.groups[0].lastSource").value("web"));
    }

    @Test
    void hidesMessagesInMetadataOnlyMode() throws Exception {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("secret detail"), "main", null, null, null, "log");
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.METADATA_ONLY);
        MockMvc mvc = buildMvc(store, properties);

        mvc.perform(get("/bootui/api/exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].message").isEmpty());
    }

    @Test
    void returnsDetailWithFramesCausesAndOccurrences() throws Exception {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(
                new IllegalStateException("wrapped", new NumberFormatException("bad")),
                "main",
                "POST",
                "/api/orders",
                "OrderController#place",
                "web");
        String id = store.groups().get(0).fingerprint();
        MockMvc mvc = buildMvc(store, new BootUiProperties());

        mvc.perform(get("/bootui/api/exceptions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.id").value(id))
                .andExpect(jsonPath("$.frames").isNotEmpty())
                .andExpect(jsonPath("$.causes[0].exceptionClassName").value("java.lang.NumberFormatException"))
                .andExpect(jsonPath("$.occurrences[0].source").value("web"))
                .andExpect(jsonPath("$.occurrences[0].requestPath").value("/api/orders"));
    }

    @Test
    void unknownExceptionReturnsNotFound() throws Exception {
        MockMvc mvc = buildMvc(new ExceptionStore(100, 25, 50), new BootUiProperties());

        mvc.perform(get("/bootui/api/exceptions/{id}", "does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void clearEmptiesTheStore() throws Exception {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");
        MockMvc mvc = buildMvc(store, new BootUiProperties());

        mvc.perform(delete("/bootui/api/exceptions")).andExpect(status().isNoContent());

        assertThat(store.totalExceptions()).isZero();
        assertThat(store.groups()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static MockMvc buildMvc(ExceptionStore store, BootUiProperties properties) {
        ObjectProvider<ExceptionStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return standaloneSetup(new ExceptionsController(provider, properties)).build();
    }
}
