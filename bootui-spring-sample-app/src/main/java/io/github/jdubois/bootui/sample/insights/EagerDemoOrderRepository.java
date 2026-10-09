package io.github.jdubois.bootui.sample.insights;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EagerDemoOrderRepository extends JpaRepository<EagerDemoOrder, Long> {

    @Query("select o from EagerDemoOrder o order by o.id")
    List<EagerDemoOrder> findWithSecondarySelects();

    @Query("select o from EagerDemoOrder o join fetch o.customer order by o.id")
    List<EagerDemoOrder> findWithCustomerJoin();
}
