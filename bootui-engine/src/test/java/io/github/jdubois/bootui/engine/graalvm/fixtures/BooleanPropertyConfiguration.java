package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("feature.enabled")
public class BooleanPropertyConfiguration {}
