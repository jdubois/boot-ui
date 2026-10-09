package io.github.jdubois.bootui.sample.insights;

import jakarta.persistence.EntityManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EagerDemoOrderSeed implements ApplicationRunner {

    public static final int ORDER_COUNT = 16;

    private final EntityManager entityManager;

    public EagerDemoOrderSeed(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existing = entityManager
                .createQuery("select count(o) from EagerDemoOrder o", Long.class)
                .getSingleResult();
        if (existing != 0) {
            return;
        }
        for (int i = 1; i <= ORDER_COUNT; i++) {
            EagerDemoCustomer customer = new EagerDemoCustomer("Customer " + i);
            entityManager.persist(customer);
            entityManager.persist(new EagerDemoOrder("Order " + i, customer));
        }
    }
}
