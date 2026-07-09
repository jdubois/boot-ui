package io.github.jdubois.bootui.autoconfigure.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailHealthContributorAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Regression test for the interaction between BootUI's mail capture wrapping and Spring Boot's Actuator
 * mail health contributor.
 *
 * <p>Spring Boot's {@code MailHealthContributorAutoConfiguration} resolves beans by the concrete
 * {@link JavaMailSenderImpl} type and asserts at least one exists. Because BootUI ships Actuator, any
 * application that adds {@code spring-boot-starter-mail} triggers this configuration. If BootUI's
 * {@link BootUiMailSenderBeanPostProcessor} replaced the {@code JavaMailSenderImpl} bean with a wrapper
 * that only implemented the {@link org.springframework.mail.javamail.JavaMailSender} interface, that
 * lookup would return empty and crash startup with {@code 'beans' must not be empty}. This test proves
 * the wrapper stays discoverable as a {@code JavaMailSenderImpl} so the two coexist.</p>
 */
class MailHealthCoexistenceTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MailSenderAutoConfiguration.class, MailHealthContributorAutoConfiguration.class))
            .withUserConfiguration(CaptureConfiguration.class)
            .withPropertyValues("spring.mail.host=localhost");

    @Test
    void mailSenderAndActuatorHealthContributorStartTogether() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            // The mail sender bean is wrapped for capture yet remains a JavaMailSenderImpl...
            assertThat(context.getBean(JavaMailSenderImpl.class)).isInstanceOf(CapturingJavaMailSender.class);
            assertThat(context.getBeansOfType(JavaMailSenderImpl.class)).isNotEmpty();
            // ...so Actuator's mail health contributor still resolves it and gets created.
            assertThat(context).hasBean("mailHealthContributor");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CaptureConfiguration {

        @Bean
        EmailCaptureService bootUiEmailCaptureService() {
            return new EmailCaptureService(new EmailStore(10), fullExposure(), false);
        }

        @Bean
        static BootUiMailSenderBeanPostProcessor bootUiMailSenderBeanPostProcessor(
                ObjectProvider<EmailCaptureService> captureServiceProvider) {
            return new BootUiMailSenderBeanPostProcessor(captureServiceProvider);
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
}
