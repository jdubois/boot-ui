package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(String.class)
public class ClasspathConditionConfiguration {}
