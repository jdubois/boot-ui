package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.lang.reflect.Proxy;

/** Triggers GRAAL-PROXY-001 by creating a JDK dynamic proxy. */
public class ProxyFactory {

    public Object proxy() {
        return Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Runnable.class},
                (proxy, method, args) -> null);
    }
}
