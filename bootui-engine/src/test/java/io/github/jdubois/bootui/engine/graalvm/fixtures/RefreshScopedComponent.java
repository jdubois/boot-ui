package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** Triggers SPRING-AOT-008 with a refresh-scoped Spring component. */
@Component
@RefreshScope
public class RefreshScopedComponent {}
