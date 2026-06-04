package io.github.jdubois.bootui.sample;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
// Hibernate Advisor demo repository for SampleCustomer, whose class intentionally triggers HIB-MAP-001.
public interface SampleCustomerRepository extends JpaRepository<SampleCustomer, Long> {

    // Keeps the entity visible in the Spring Data panel while documenting the HIB-MAP-001 fixture.
    List<SampleCustomer> findByNameContainingIgnoreCase(String name);
}
