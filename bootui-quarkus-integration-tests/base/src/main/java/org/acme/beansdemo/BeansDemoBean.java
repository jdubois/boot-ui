package org.acme.beansdemo;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A trivial application bean used by {@code BootUiQuarkusBeansResourceTest} to prove the Beans panel lists
 * the consumer application's own Arc beans.
 *
 * <p>It is {@code @Unremovable} on purpose: Arc removes unused beans at build time as an optimization, so
 * an otherwise-uninjected bean would not appear in the container's bean inventory. A real consumer
 * application's beans are typically retained because they are injected/used; this annotation reproduces
 * that "retained bean" state deterministically without adding a synthetic injection point.</p>
 */
@ApplicationScoped
@Unremovable
public class BeansDemoBean {

    public String describe() {
        return "bootui-quarkus beans panel integration sample";
    }
}
