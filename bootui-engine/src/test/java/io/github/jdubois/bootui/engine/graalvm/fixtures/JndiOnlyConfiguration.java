package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.condition.ConditionalOnJndi;
import org.springframework.context.annotation.Configuration;

/** JNDI is equally unavailable on the AOT build machine and in a standalone native executable. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnJndi
public class JndiOnlyConfiguration {}
