package io.github.jdubois.bootui.sample;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
// Hibernate Advisor demo repository for SampleOrder, whose mappings intentionally trigger
// HIB-FETCH-001/HIB-MAP-002/HIB-MAP-003.
public interface SampleOrderRepository extends JpaRepository<SampleOrder, Long> {

    // Keeps the enum-mapped entity visible in Spring Data; SampleOrder.status intentionally triggers HIB-MAP-003.
    List<SampleOrder> findByStatus(SampleOrderStatus status);
}
