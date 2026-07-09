package io.github.jdubois.bootui.autoconfigure.mail;

import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wraps every {@link JavaMailSender} bean with {@link CapturingJavaMailSender} after initialization, so
 * every outgoing email is captured for the Email Viewer panel before being handed to the real sender.
 *
 * <p>The capture service is resolved lazily through an {@link ObjectProvider} so this post-processor
 * does not force early creation of unrelated beans. It fails open: if wrapping fails, the original bean
 * is returned unchanged so the application's mail sending is never compromised. Capture is unconditional
 * once BootUI is active - like {@code SqlTraceDataSourceBeanPostProcessor}, per-panel enable/read-only
 * toggles (@code bootui.panels.email.*}) only gate the HTTP API, not capture itself.</p>
 */
public final class BootUiMailSenderBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<EmailCaptureService> captureServiceProvider;

    public BootUiMailSenderBeanPostProcessor(ObjectProvider<EmailCaptureService> captureServiceProvider) {
        this.captureServiceProvider = captureServiceProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof JavaMailSender javaMailSender) || bean instanceof CapturingJavaMailSender) {
            return bean;
        }
        EmailCaptureService captureService = captureServiceProvider.getIfAvailable();
        if (captureService == null) {
            return bean;
        }
        return new CapturingJavaMailSender(javaMailSender, captureService);
    }
}
