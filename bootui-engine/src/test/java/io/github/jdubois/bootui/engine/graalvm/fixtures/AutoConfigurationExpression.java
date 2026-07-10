package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** Verifies that unsafe bean references remain visible inside deliberate auto-configuration. */
@AutoConfiguration
@ConditionalOnExpression("#{@someBean.enabled}")
public class AutoConfigurationExpression {}
