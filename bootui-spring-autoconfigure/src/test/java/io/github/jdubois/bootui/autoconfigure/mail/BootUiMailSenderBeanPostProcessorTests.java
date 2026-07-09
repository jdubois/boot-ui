package io.github.jdubois.bootui.autoconfigure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class BootUiMailSenderBeanPostProcessorTests {

    @Test
    void wrapsJavaMailSenderBeans() {
        EmailCaptureService captureService = new EmailCaptureService(new EmailStore(10), fullExposure(), false);
        BootUiMailSenderBeanPostProcessor postProcessor =
                new BootUiMailSenderBeanPostProcessor(captureServiceProvider(captureService));

        JavaMailSenderImpl bean = new JavaMailSenderImpl();
        Object result = postProcessor.postProcessAfterInitialization(bean, "mailSender");

        assertThat(result).isInstanceOf(JavaMailSender.class).isNotSameAs(bean);
    }

    @Test
    void leavesUnrelatedBeansUnchanged() {
        EmailCaptureService captureService = new EmailCaptureService(new EmailStore(10), fullExposure(), false);
        BootUiMailSenderBeanPostProcessor postProcessor =
                new BootUiMailSenderBeanPostProcessor(captureServiceProvider(captureService));

        Object bean = new Object();
        Object result = postProcessor.postProcessAfterInitialization(bean, "somethingElse");

        assertThat(result).isSameAs(bean);
    }

    @Test
    void doesNotDoubleWrapAlreadyCapturingSender() {
        EmailCaptureService captureService = new EmailCaptureService(new EmailStore(10), fullExposure(), false);
        BootUiMailSenderBeanPostProcessor postProcessor =
                new BootUiMailSenderBeanPostProcessor(captureServiceProvider(captureService));

        JavaMailSender alreadyWrapped = new CapturingJavaMailSender(new JavaMailSenderImpl(), captureService);
        Object result = postProcessor.postProcessAfterInitialization(alreadyWrapped, "mailSender");

        assertThat(result).isSameAs(alreadyWrapped);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EmailCaptureService> captureServiceProvider(EmailCaptureService captureService) {
        ObjectProvider<EmailCaptureService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(captureService);
        return provider;
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
