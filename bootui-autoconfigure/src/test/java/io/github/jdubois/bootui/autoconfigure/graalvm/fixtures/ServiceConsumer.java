package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.util.ServiceLoader;

/** Triggers GRAAL-SERVICE-001 by loading providers through the ServiceLoader. */
public class ServiceConsumer {

    public ServiceLoader<Runnable> services() {
        return ServiceLoader.load(Runnable.class);
    }
}
