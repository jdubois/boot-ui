package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

public final class AotFriendlyBeanRegistrar implements BeanRegistrar {

    @Override
    public void register(BeanRegistry registry, Environment environment) {
        registry.registerBean(Object.class, spec -> spec.supplier(context -> new Object()));
    }
}
