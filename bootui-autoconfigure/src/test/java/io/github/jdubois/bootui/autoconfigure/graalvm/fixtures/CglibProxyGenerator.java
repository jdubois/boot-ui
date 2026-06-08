package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import org.springframework.cglib.proxy.Enhancer;

/** Triggers GRAAL-CLASSGEN-001 by generating a class at run time through a CGLIB Enhancer. */
public class CglibProxyGenerator {

    public Object proxy() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Object.class);
        return enhancer.create();
    }
}
