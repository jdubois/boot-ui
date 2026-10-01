package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.cloud.context.config.annotation.RefreshScope;

/** Not a Spring component, so SPRING-AOT-008 must stay quiet. */
@RefreshScope
public class RefreshScopedNonBean {}
