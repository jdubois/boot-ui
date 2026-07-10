package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;

/** Verifies that property-only expressions retain the normal AOT-condition severity. */
@Configuration
@ConditionalOnExpression("${feature.enabled:false}")
public class PropertyExpressionConfiguration {}
