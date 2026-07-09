package org.acme.beansdemo;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@Unremovable
public class BeanGraphConsumer {

    @Inject
    BeansDemoBean beansDemoBean;

    public String describeDependency() {
        return beansDemoBean.describe();
    }
}
