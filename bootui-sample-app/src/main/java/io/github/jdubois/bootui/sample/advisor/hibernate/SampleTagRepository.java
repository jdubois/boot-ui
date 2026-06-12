package io.github.jdubois.bootui.sample.advisor.hibernate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
// Hibernate Advisor demo repository for SampleTag, used by SampleOrder.tags to trigger HIB-MAP-002.
public interface SampleTagRepository extends JpaRepository<SampleTag, Long> {

    // Keeps the tag side of the HIB-MAP-002 many-to-many fixture visible in Spring Data.
    List<SampleTag> findByLabelContainingIgnoreCase(String label);
}
