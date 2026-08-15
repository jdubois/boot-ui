package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;

/** Triggers the dedicated SPRING-AOT-005 treatment for bean-referencing expressions. */
@Configuration
@ConditionalOnExpression("#{@someBean.enabled}")
public class ExpressionConfiguration {}
