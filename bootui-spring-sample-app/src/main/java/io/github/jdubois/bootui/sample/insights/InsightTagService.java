package io.github.jdubois.bootui.sample.insights;

import io.github.jdubois.bootui.sample.advisor.hibernate.SampleTag;
import io.github.jdubois.bootui.sample.advisor.hibernate.SampleTagRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Hibernate seeds of Runtime Insights ({@code docs/PLAN-v2.md} M4-9): saving a tag and then counting the tags,
 * three times in one transaction, makes Hibernate write each pending insert before the count, which {@code
 * orm-auto-flush} reports; counting first and saving afterwards writes once, at commit, and must not be reported.
 * {@code readonly-candidate-transaction} reads a read-write transaction that only reads, which the methods marked
 * {@code @Transactional} without {@code readOnly} below seed, with the writes that must keep it from being reported;
 * their pages are sorted, so they are not also the unsorted pages {@code paged-without-order} reports, which {@code
 * unsortedPage} seeds beside its sorted counterexample. Never copy these methods into an application.
 */
@Service
public class InsightTagService {

    /** The tags the export seeds read: more than the 500 entities one flush must hold to be reported. */
    static final int EXPORTED_TAGS = 600;

    private static final int SUMMARY_TAGS = 5;

    private static final Sort BY_ID = Sort.by("id");

    private final SampleTagRepository tags;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public InsightTagService(SampleTagRepository tags, EntityManager entityManager, JdbcTemplate jdbc) {
        this.tags = tags;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Transactional
    public long saveThenCountEachTime() {
        long count = 0;
        for (int i = 0; i < 3; i++) {
            tags.save(new SampleTag("insight-auto-flush-" + i));
            count = tags.count();
        }
        return count;
    }

    @Transactional
    public long countThenSave() {
        long count = 0;
        for (int i = 0; i < 3; i++) {
            count = tags.count();
        }
        for (int i = 0; i < 3; i++) {
            tags.save(new SampleTag("insight-read-then-write-" + i));
        }
        return count;
    }

    /**
     * {@code connection-reacquisition}: three reads, each in a transaction of its own, so the request's Hibernate
     * session returns its connection after each one and borrows another for the next. The caller keeps the session
     * open across them, as open-in-view does; {@link InsightOpenSessionConfig} does so for the seed routes only.
     */
    public long countTagsOneTransactionAtATime() {
        long count = 0;
        for (int i = 0; i < 3; i++) {
            count = tags.count();
        }
        return count;
    }

    /** The counterexample: the same three reads in one transaction, which borrows one connection. */
    @Transactional(readOnly = true)
    public long countTagsInOneTransaction() {
        long count = 0;
        for (int i = 0; i < 3; i++) {
            count = tags.count();
        }
        return count;
    }

    /** Fills the tag table for the export seeds, once, at startup and outside every request. */
    @Transactional
    public void ensureExportedTags() {
        List<SampleTag> missing = new ArrayList<>();
        for (long i = tags.count(); i < EXPORTED_TAGS; i++) {
            missing.add(new SampleTag("insight-export-" + i));
        }
        tags.saveAll(missing);
    }

    /** {@code large-persistence-context}: every tag loaded as a managed entity, then flushed at commit. */
    @Transactional
    public int exportEveryTag() {
        return tags.findAll().size();
    }

    /** The counterexample: the same tags' labels through a projection, so no entity is managed. */
    @Transactional
    public int exportLabels() {
        return entityManager
                .createQuery("select t.label from SampleTag t", String.class)
                .getResultList()
                .size();
    }

    /**
     * {@code readonly-candidate-transaction}: a read-write transaction that loads a few tags, so Hibernate dirty-checks
     * them at commit, and changes nothing.
     */
    @Transactional
    public int summary() {
        return tags.findAll(PageRequest.of(0, SUMMARY_TAGS, BY_ID)).getNumberOfElements();
    }

    /** The counterexample: the same read, then a label that changes, which Hibernate finds dirty and updates. */
    @Transactional
    public int summaryThenRelabel() {
        List<SampleTag> page =
                tags.findAll(PageRequest.of(0, SUMMARY_TAGS, BY_ID)).getContent();
        SampleTag first = page.get(0);
        first.relabel(
                first.getLabel().endsWith("*")
                        ? first.getLabel().substring(0, first.getLabel().length() - 1)
                        : first.getLabel() + "*");
        return page.size();
    }

    /** The counterexample: the same read, then a write through JDBC, which Hibernate never sees as a dirty entity. */
    @Transactional
    public int summaryThenJdbcWrite() {
        List<SampleTag> page =
                tags.findAll(PageRequest.of(0, SUMMARY_TAGS, BY_ID)).getContent();
        jdbc.update(
                "update sample_advisor_tags set label = label where id = ?",
                page.get(0).getId());
        return page.size();
    }

    /** {@code paged-without-order}: the second page of tags with no sort, which Hibernate limits without an order. */
    public int unsortedPage() {
        return tags.findAll(PageRequest.of(1, SUMMARY_TAGS)).getNumberOfElements();
    }

    /** The counterexample: the same page sorted by id, which Hibernate orders before it limits. */
    public int sortedPage() {
        return tags.findAll(PageRequest.of(1, SUMMARY_TAGS, BY_ID)).getNumberOfElements();
    }
}
