package io.github.jdubois.bootui.autoconfigure.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.web.servlet.MockMvc;

class JmsControllerTests {

    @Test
    void reportsUnavailableWhenNoJmsTemplateBeanIsPresent() throws Exception {
        MockMvc mvc = buildMvc(new JmsActivityRecorder(true, true, 200, 16), null);

        mvc.perform(get("/bootui/api/jms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("No JmsTemplate bean is present"))
                .andExpect(jsonPath("$.maxEntries").value(200))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void listsOnlyCapturedJmsMetadataWhenJmsTemplateIsPresent() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 200, 16);
        recorder.recordProduce("orders", "ID:produce-1", 3L, true, null);
        recorder.recordConsume("events", "ID:consume-1", 12L, false, "jakarta.jms.JMSException", "updates", "listener");
        MockMvc mvc = buildMvc(recorder, mock(JmsTemplate.class));

        mvc.perform(get("/bootui/api/jms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.capturing").value(true))
                .andExpect(jsonPath("$.captureMessageIdEnabled").value(true))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalCaptured").value(2))
                .andExpect(jsonPath("$.messages[0].direction").value("CONSUME"))
                .andExpect(jsonPath("$.messages[0].destination").value("events"))
                .andExpect(jsonPath("$.messages[0].messageId").isNotEmpty())
                .andExpect(jsonPath("$.messages[0].failureType").value("jakarta.jms.JMSException"))
                .andExpect(jsonPath("$.messages[0].subscriptionName").value("updates"))
                .andExpect(jsonPath("$.messages[0].listenerId").value("listener"))
                .andExpect(jsonPath("$.messages[1].direction").value("PRODUCE"));
    }

    @Test
    void reflectsDisabledCaptureAndMessageIdHashingOnTheReport() throws Exception {
        MockMvc mvc = buildMvc(new JmsActivityRecorder(false, false, 200, 16), mock(JmsTemplate.class));

        mvc.perform(get("/bootui/api/jms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.capturing").value(false))
                .andExpect(jsonPath("$.captureMessageIdEnabled").value(false));
    }

    @Test
    void reportsAvailableWhenMultipleJmsTemplateBeansArePresent() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 200, 16);
        @SuppressWarnings("unchecked")
        ObjectProvider<JmsTemplate> templates = mock(ObjectProvider.class);
        when(templates.stream()).thenReturn(Stream.of(mock(JmsTemplate.class), mock(JmsTemplate.class)));
        JmsController controller = new JmsController(provider(recorder), templates, new BootUiProperties());
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/jms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void reportsUnavailableInANativeImage() throws Exception {
        JmsController controller =
                new JmsController(
                        provider(new JmsActivityRecorder(true, true, 200, 16)),
                        provider(mock(JmsTemplate.class)),
                        new BootUiProperties()) {
                    @Override
                    boolean nativeImageDetected() {
                        return true;
                    }
                };
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/jms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason")
                        .value("JMS capture is not available when running as a GraalVM native image"));
    }

    @Test
    void clearRemovesAllCapturedMessages() throws Exception {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 200, 16);
        recorder.recordProduce("orders", "ID:produce-1", 3L, true, null);
        MockMvc mvc = buildMvc(recorder, mock(JmsTemplate.class));

        mvc.perform(delete("/bootui/api/jms")).andExpect(status().isNoContent());

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void clearIsANoOpWhenNoRecorderIsPresent() throws Exception {
        MockMvc mvc = buildMvc(null, mock(JmsTemplate.class));

        mvc.perform(delete("/bootui/api/jms")).andExpect(status().isNoContent());
    }

    private MockMvc buildMvc(JmsActivityRecorder recorder, JmsTemplate jmsTemplate) {
        JmsController controller = new JmsController(provider(recorder), provider(jmsTemplate), new BootUiProperties());
        return standaloneSetup(controller).build();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.stream()).thenReturn(value == null ? Stream.empty() : Stream.of(value));
        return provider;
    }
}
