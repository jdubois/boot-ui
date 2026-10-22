package io.github.jdubois.bootui.webfluxsample.insights;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writes behind the {@code unbatched-write-loop} seed and its counterexample: the same twelve updates of the
 * sample's notes, one statement execution each or one JDBC batch. Each update sets a note's body to itself, so no
 * data changes.
 */
@Service
public class InsightNoteWriter {

    private static final String TOUCH = "update sample_note set body = body where id = ?";

    private final JdbcTemplate jdbc;

    public InsightNoteWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One statement execution per note, in one transaction, each paying its own round trip. */
    @Transactional
    public int touchOneByOne(int notes) {
        for (long id = 1; id <= notes; id++) {
            jdbc.update(TOUCH, id);
        }
        return notes;
    }

    /** The counterexample: the same updates sent as one JDBC batch. */
    @Transactional
    public int touchInOneBatch(int notes) {
        List<Object[]> batch = new ArrayList<>();
        for (long id = 1; id <= notes; id++) {
            batch.add(new Object[] {id});
        }
        jdbc.batchUpdate(TOUCH, batch);
        return notes;
    }
}
