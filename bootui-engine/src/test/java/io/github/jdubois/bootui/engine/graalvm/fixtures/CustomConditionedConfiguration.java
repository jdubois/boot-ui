package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Verifies that an application-defined composed conditional annotation is detected. */
@Configuration
@CustomConditionedConfiguration.CustomCondition
public class CustomConditionedConfiguration {

    @Retention(RetentionPolicy.RUNTIME)
    @Conditional(AlwaysCondition.class)
    public @interface CustomCondition {}

    static final class AlwaysCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return true;
        }
    }
}
