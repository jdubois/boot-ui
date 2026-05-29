package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.web.CamelController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;

@AutoConfiguration(
        after = BootUiAutoConfiguration.class,
        afterName = "org.apache.camel.spring.boot.CamelAutoConfiguration")
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.apache.camel.CamelContext")
@ConditionalOnBean(type = "org.apache.camel.CamelContext")
@Import(CamelController.class)
class BootUiCamelAutoConfiguration {}
