package io.github.jdubois.bootui.sample;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
// Hibernate Advisor demo: this repository triggers checks related to the SampleDevice entity.
public interface SampleDeviceRepository extends JpaRepository<SampleDevice, Long> {}
