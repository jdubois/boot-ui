package org.acme.beansdemo;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BeansDemoDependency {

    public String value() {
        return "dependency";
    }
}
