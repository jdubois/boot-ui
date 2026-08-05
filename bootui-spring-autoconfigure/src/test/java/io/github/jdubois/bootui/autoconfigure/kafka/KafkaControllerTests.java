package io.github.jdubois.bootui.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.web.servlet.MockMvc;

class KafkaControllerTests {

    @SuppressWarnings("unchecked")
    private final ProducerFactory<Object, Object> producerFactory = mock(ProducerFactory.class);

    @Test
    void reportsUnavailableWhenNoKafkaTemplateBeanIsPresent() throws Exception {
        MockMvc mvc = buildMvc(new KafkaActivityRecorder(true, true, 200, 200), null);

        mvc.perform(get("/bootui/api/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("No KafkaTemplate bean is present"))
                .andExpect(jsonPath("$.maxEntries").value(200))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void listsCapturedKafkaActivityWhenKafkaTemplateIsPresent() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 200, 200);
        recorder.recordProduce("orders", 0, "order-42", null, true, null);
        recorder.recordConsume("orders", 0, 41L, "order-42", 12L, true, null, "orders-group", "orderListener");
        MockMvc mvc = buildMvc(recorder, new KafkaTemplate<>(producerFactory));

        mvc.perform(get("/bootui/api/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.capturing").value(true))
                .andExpect(jsonPath("$.captureKeyEnabled").value(true))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalCaptured").value(2))
                .andExpect(jsonPath("$.messages[0].direction").value("CONSUME"))
                .andExpect(jsonPath("$.messages[0].topic").value("orders"))
                .andExpect(jsonPath("$.messages[0].groupId").value("orders-group"))
                .andExpect(jsonPath("$.messages[0].listenerId").value("orderListener"))
                .andExpect(jsonPath("$.messages[1].direction").value("PRODUCE"));
    }

    @Test
    void reflectsDisabledCaptureAndKeyHashingOnTheReport() throws Exception {
        MockMvc mvc = buildMvc(new KafkaActivityRecorder(false, false, 200, 200), new KafkaTemplate<>(producerFactory));

        mvc.perform(get("/bootui/api/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.capturing").value(false))
                .andExpect(jsonPath("$.captureKeyEnabled").value(false));
    }

    @Test
    void clearRemovesAllCapturedMessages() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 200, 200);
        recorder.recordProduce("orders", 0, "order-42", null, true, null);
        MockMvc mvc = buildMvc(recorder, new KafkaTemplate<>(producerFactory));

        mvc.perform(delete("/bootui/api/kafka")).andExpect(status().isNoContent());

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void clearIsANoOpWhenNoRecorderIsPresent() throws Exception {
        MockMvc mvc = buildMvc(null, new KafkaTemplate<>(producerFactory));

        mvc.perform(delete("/bootui/api/kafka")).andExpect(status().isNoContent());
    }

    private MockMvc buildMvc(KafkaActivityRecorder recorder, KafkaTemplate<?, ?> kafkaTemplate) {
        KafkaController controller =
                new KafkaController(provider(recorder), provider(kafkaTemplate), new BootUiProperties());
        return standaloneSetup(controller).build();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
