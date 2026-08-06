package io.github.jdubois.bootui.autoconfigure.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;

class RabbitControllerTests {

    @Test
    void reportsUnavailableWhenNoRabbitTemplateBeanIsPresent() throws Exception {
        MockMvc mvc = buildMvc(new RabbitActivityRecorder(true, false, 200, 16), null);

        mvc.perform(get("/bootui/api/rabbitmq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("No RabbitTemplate bean is present"))
                .andExpect(jsonPath("$.maxEntries").value(200));
    }

    @Test
    void listsOnlyCapturedRabbitMetadataWhenRabbitTemplateIsPresent() throws Exception {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 200, 16);
        recorder.recordPublish("orders", "order.created", null, true, null, "customer-123");
        recorder.recordConsume("orders", "order.created", "fulfillment", 12L, false, "payload=secret", "customer-123");
        MockMvc mvc = buildMvc(recorder, mock(RabbitTemplate.class));

        mvc.perform(get("/bootui/api/rabbitmq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.captureCorrelationIdEnabled").value(true))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.messages[0].direction").value("CONSUME"))
                .andExpect(jsonPath("$.messages[0].queue").value("fulfillment"))
                .andExpect(jsonPath("$.messages[0].errorMessage").value("Message processing failed"))
                .andExpect(jsonPath("$.messages[0].correlationId").isNotEmpty())
                .andExpect(jsonPath("$.messages[1].direction").value("PUBLISH"));
    }

    @Test
    void reportsAvailableWhenMultipleRabbitTemplateBeansArePresent() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitTemplate> templates = mock(ObjectProvider.class);
        RabbitTemplate first = mock(RabbitTemplate.class);
        RabbitTemplate second = mock(RabbitTemplate.class);
        when(templates.stream()).thenReturn(Stream.of(first, second));
        RabbitController controller = new RabbitController(
                provider(new RabbitActivityRecorder(true, false, 200, 16)), templates, new BootUiProperties());
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/rabbitmq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void honorsCustomApiPath() throws Exception {
        RabbitController controller = new RabbitController(
                provider(new RabbitActivityRecorder(true, false, 200, 16)),
                provider(mock(RabbitTemplate.class)),
                new BootUiProperties());
        MockMvc mvc = standaloneSetup(controller)
                .addPlaceholderValue("bootui.api-path", "/internal/bootui-api")
                .build();

        mvc.perform(get("/internal/bootui-api/rabbitmq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void clearRemovesAllCapturedMessages() throws Exception {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, false, 200, 16);
        recorder.recordPublish("orders", "order.created", null, true, null, null);
        MockMvc mvc = buildMvc(recorder, mock(RabbitTemplate.class));

        mvc.perform(delete("/bootui/api/rabbitmq")).andExpect(status().isNoContent());

        assertThat(recorder.recent()).isEmpty();
    }

    private MockMvc buildMvc(RabbitActivityRecorder recorder, RabbitTemplate rabbitTemplate) {
        RabbitController controller =
                new RabbitController(provider(recorder), provider(rabbitTemplate), new BootUiProperties());
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
