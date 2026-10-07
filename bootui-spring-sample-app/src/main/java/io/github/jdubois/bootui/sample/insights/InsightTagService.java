package io.github.jdubois.bootui.sample.insights;

import io.github.jdubois.bootui.sample.advisor.hibernate.SampleTag;
import io.github.jdubois.bootui.sample.advisor.hibernate.SampleTagRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Hibernate seeds of Runtime Insights ({@code docs/PLAN-v2.md} M4-9): saving a tag and then counting the tags,
 * three times in one transaction, makes Hibernate write each pending insert before the count, which {@code
 * orm-auto-flush} reports; counting first and saving afterwards writes once, at commit, and must not be reported.
 * Never copy these methods into an application.
 */
@Service
public class InsightTagService {

    /** The tags the export seeds read: more than the 500 entities one flush must hold to be reported. */
    static final int EXPORTED_TAGS = 600;

    private final SampleTagRepository tags;
    private final EntityManager entityManager;

    public InsightTagService(SampleTagRepository tags, EntityManager entityManager) {
        this.tags = tags;
        this.entityManager = entityManager;
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
}
