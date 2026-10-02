package io.github.jdubois.bootui.sample.insights;

import io.github.jdubois.bootui.sample.advisor.hibernate.SampleTag;
import io.github.jdubois.bootui.sample.advisor.hibernate.SampleTagRepository;
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

    private final SampleTagRepository tags;

    public InsightTagService(SampleTagRepository tags) {
        this.tags = tags;
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
}
