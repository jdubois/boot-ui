package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Proxy;

/** Triggers GRAAL-PROXY-001 by resolving a JDK proxy class via Proxy.getProxyClass. */
public class ProxyClassFactory {

    @SuppressWarnings("deprecation")
    public Class<?> proxyClass() {
        return Proxy.getProxyClass(getClass().getClassLoader(), Runnable.class);
    }
}
