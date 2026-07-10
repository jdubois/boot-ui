package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** Verifies that deliberate condition-driven auto-configuration is not reported. */
@AutoConfiguration
@ConditionalOnExpression("${feature.enabled:false}")
public class IntentionalAutoConfiguration {}
