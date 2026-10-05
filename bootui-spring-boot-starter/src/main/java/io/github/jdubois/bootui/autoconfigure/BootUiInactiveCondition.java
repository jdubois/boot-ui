package io.github.jdubois.bootui.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Exact negation of {@link BootUiActivationCondition}: matches when BootUI resolves to
 * <em>inactive</em> for the current environment.
 *
 * <p>Both conditions delegate to the same {@link BootUiActivationCondition#resolve} call, so there is
 * a single activation decision and no way for the two polarities to drift apart. This is what
 * {@link BootUiShellGuardAutoConfiguration} uses to wire the only piece of BootUI that must exist
 * while the console is switched off.</p>
 */
public class BootUiInactiveCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !BootUiActivationCondition.resolve(context.getEnvironment(), context.getClassLoader())
                .enabled();
    }
}
