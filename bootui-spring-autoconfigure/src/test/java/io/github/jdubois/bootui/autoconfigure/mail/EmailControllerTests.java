package io.github.jdubois.bootui.autoconfigure.mail;

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
import io.github.jdubois.bootui.engine.email.CapturedEmail;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.servlet.MockMvc;

class EmailControllerTests {

    @Test
    void reportsUnavailableWhenNoJavaMailSenderBeanIsPresent() throws Exception {
        MockMvc mvc = buildMvc(new EmailStore(100), null);

        mvc.perform(get("/bootui/api/email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("No JavaMailSender bean is present"))
                .andExpect(jsonPath("$.maxEntries").value(100));
    }

    @Test
    void listsCapturedEmailsWhenMailSenderIsPresent() throws Exception {
        EmailStore store = new EmailStore(100);
        store.capture(
                CapturedEmail.builder()
                        .from("noreply@example.com")
                        .to(List.of("customer@example.com"))
                        .subject("Order shipped")
                        .textBody("Your order is on the way")
                        .build(),
                true);
        MockMvc mvc = buildMvc(store, new JavaMailSenderImpl());

        mvc.perform(get("/bootui/api/email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.messages[0].subject").value("Order shipped"));
    }

    @Test
    void downloadsEmlFile() throws Exception {
        EmailStore store = new EmailStore(100);
        store.capture(
                CapturedEmail.builder()
                        .from("noreply@example.com")
                        .to(List.of("customer@example.com"))
                        .subject("Order shipped")
                        .textBody("Your order is on the way")
                        .build(),
                true);
        String id = store.list().get(0).id();
        MockMvc mvc = buildMvc(store, new JavaMailSenderImpl());

        byte[] body = mvc.perform(get("/bootui/api/email/{id}/eml", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        String eml = new String(body, StandardCharsets.UTF_8);
        assertThat(eml).contains("Subject: Order shipped");
        assertThat(eml).contains("Your order is on the way");
    }

    @Test
    void returns404ForUnknownId() throws Exception {
        MockMvc mvc = buildMvc(new EmailStore(100), new JavaMailSenderImpl());

        mvc.perform(get("/bootui/api/email/{id}", "does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void clearRemovesAllCapturedEmails() throws Exception {
        EmailStore store = new EmailStore(100);
        store.capture(
                CapturedEmail.builder()
                        .from("noreply@example.com")
                        .subject("Test")
                        .build(),
                true);
        MockMvc mvc = buildMvc(store, new JavaMailSenderImpl());

        mvc.perform(delete("/bootui/api/email")).andExpect(status().isNoContent());

        assertThat(store.size()).isZero();
    }

    @SuppressWarnings("unchecked")
    private MockMvc buildMvc(EmailStore store, JavaMailSender mailSender) {
        EmailCaptureService captureService = new EmailCaptureService(store, fullExposure(), false);
        ObjectProvider<EmailCaptureService> captureServiceProvider = mock(ObjectProvider.class);
        when(captureServiceProvider.getIfAvailable()).thenReturn(captureService);
        ObjectProvider<JavaMailSender> mailSenderProvider = mock(ObjectProvider.class);
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        EmailController controller =
                new EmailController(captureServiceProvider, mailSenderProvider, new BootUiProperties());
        return standaloneSetup(controller).build();
    }

    private static ExposurePolicy fullExposure() {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return ValueExposure.FULL;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
    }
}
