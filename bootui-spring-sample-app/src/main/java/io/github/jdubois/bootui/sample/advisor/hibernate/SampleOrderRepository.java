package io.github.jdubois.bootui.sample.advisor.hibernate;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
// Hibernate Advisor demo repository for SampleOrder, whose mappings intentionally trigger
// HIB-FETCH-001/HIB-FETCH-004/HIB-MAP-002/HIB-MAP-003/HIB-MAP-004/HIB-MAP-005,
// plus the HIB-QUERY-* checks below.
public interface SampleOrderRepository extends JpaRepository<SampleOrder, Long> {

    // Keeps the enum-mapped entity visible in Spring Data; SampleOrder.status intentionally omits @Enumerated.
    List<SampleOrder> findByStatus(SampleOrderStatus status);

    // Intentionally combines Pageable with a collection fetch join to trigger HIB-FETCH-003.
    @Query(
            value = "select o from SampleOrder o left join fetch o.tags where o.status = :status",
            countQuery = "select count(o) from SampleOrder o where o.status = :status")
    Page<SampleOrder> findPageWithTags(SampleOrderStatus status, Pageable pageable);

    // Intentionally uses a collection parameter in an IN predicate to trigger HIB-CONFIG-009 when padding is disabled.
    @Query("select o from SampleOrder o where o.id in :ids")
    List<SampleOrder> findByIds(Collection<Long> ids);

    // Intentionally returns a Stream to trigger HIB-QUERY-002 (must run inside a transactional, read-only scope).
    @Query("select o from SampleOrder o")
    Stream<SampleOrder> streamAll();

    // Intentionally @Modifying without clearAutomatically/flushAutomatically to trigger HIB-QUERY-001
    // (persistence context can hold stale entities after the bulk update).
    @Modifying
    @Query("update SampleOrder o set o.status = :status")
    int markAllAs(SampleOrderStatus status);

    // Intentionally a native paged @Query without countQuery to trigger HIB-QUERY-003 (Spring Data cannot derive
    // COUNT).
    @Query(value = "select * from sample_advisor_orders where status = :status", nativeQuery = true)
    Page<SampleOrder> findPageNative(String status, Pageable pageable);

    // Intentionally a derived deleteBy method to trigger HIB-QUERY-004 (loads entities first, then deletes one by one).
    long deleteByStatus(SampleOrderStatus status);
}
