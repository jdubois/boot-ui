package io.github.jdubois.bootui.autoconfigure.explorer;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.engine.explorer.LocalInvocationCapture;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Role;
import org.springframework.core.NativeDetector;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Default-on JVM/MVC bean capture. Installing or visiting Explorer never enables host tracing. */
@AutoConfiguration(
        after = BootUiAutoConfiguration.class,
        afterName = {
            "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration",
            "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration"
        })
@Conditional({BootUiActivationCondition.class, BootUiExplorerAutoConfiguration.JvmOnly.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(
        name = {
            "org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator",
            "io.opentelemetry.api.trace.Span",
            "jakarta.servlet.http.HttpServletRequest"
        })
@ConditionalOnBean(value = TelemetryStore.class, type = "io.opentelemetry.api.OpenTelemetry")
@ConditionalOnProperty(prefix = "bootui.explorer", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "bootui.telemetry", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "bootui.panels.activity", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "bootui.panels.explorer", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "bootui.panels.beans", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "bootui.panels.traces", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BootUiExplorerAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static BeanDefinitionRegistryPostProcessor bootUiExplorerAutoProxyRegistration(Environment environment) {
        return registry -> {
            boolean existing = registry.containsBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME);
            AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
            if (!existing && environment.getProperty("spring.aop.proxy-target-class", Boolean.class, true)) {
                AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
            }
        };
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ExplorerBeanAdvisor bootUiExplorerBeanAdvisor(
            ConfigurableListableBeanFactory beanFactory, ObjectProvider<SpringInvocationContext> contexts) {
        return new ExplorerBeanAdvisor(beanFactory, contexts);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ExplorerBeanAdvisor.ProxyAttachment bootUiExplorerProxyAttachment(
            ObjectProvider<ExplorerBeanAdvisor> advisors) {
        return new ExplorerBeanAdvisor.ProxyAttachment(advisors);
    }

    @Bean
    SpringInvocationContext bootUiExplorerInvocationContext(
            BootUiProperties properties,
            TelemetryStore store,
            BootUiSelfDataFilter selfDataFilter,
            Environment environment) {
        LocalInvocationCapture capture = new LocalInvocationCapture(
                store,
                new SpringTelemetrySettings(properties),
                selfDataFilter.telemetryClassifier(),
                () -> environment.getProperty("bootui.explorer.enabled", Boolean.class, true)
                        && properties.isPanelEnabled("activity")
                        && properties.isPanelEnabled("explorer")
                        && properties.isPanelEnabled("beans")
                        && properties.isPanelEnabled("traces"),
                environment.getProperty("spring.application.name"));
        return new SpringInvocationContext(capture);
    }

    static final class JvmOnly implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !NativeDetector.inNativeImage()
                    && !AotDetector.useGeneratedArtifacts()
                    && context.getEnvironment().getProperty("org.graalvm.nativeimage.imagecode") == null;
        }
    }
}
