package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;

/** Triggers the stronger SPRING-AOT-003 treatment for expression-based conditions. */
@Configuration
@ConditionalOnExpression("#{@someBean.enabled}")
public class ExpressionConfiguration {}
