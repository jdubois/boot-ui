package io.github.jdubois.bootui.webfluxsample.notes;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain blocking {@link JdbcTemplate} access to the {@code sample_note} table. WebFlux has no first-class
 * relational driver story as simple as JDBC, so many real reactive applications keep blocking JDBC for
 * relational data and push it off the Netty event loop at the call site (see {@link NoteController}) -
 * this repository is written the same way a servlet app would write it.
 */
@Repository
public class NoteRepository {

    private static final int PAGE_SIZE = 3;

    private final JdbcTemplate jdbcTemplate;

    public NoteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Note> findAll() {
        return jdbcTemplate.query(
                "select id, title, body from sample_note order by id",
                (rs, rowNum) -> new Note(rs.getLong("id"), rs.getString("title"), rs.getString("body")));
    }

    /** The second page of notes, three at a time, with no order: the database may return any three. */
    public List<Note> findUnsortedPage() {
        return jdbcTemplate.query(
                "select id, title, body from sample_note limit ? offset ?",
                (rs, rowNum) -> new Note(rs.getLong("id"), rs.getString("title"), rs.getString("body")),
                PAGE_SIZE,
                PAGE_SIZE);
    }

    /** The same page ordered by id, so every request returns the same three notes. */
    public List<Note> findSortedPage() {
        return jdbcTemplate.query(
                "select id, title, body from sample_note order by id limit ? offset ?",
                (rs, rowNum) -> new Note(rs.getLong("id"), rs.getString("title"), rs.getString("body")),
                PAGE_SIZE,
                PAGE_SIZE);
    }

    public Optional<Note> findById(long id) {
        try {
            Note note = jdbcTemplate.queryForObject(
                    "select id, title, body from sample_note where id = ?",
                    (rs, rowNum) -> new Note(rs.getLong("id"), rs.getString("title"), rs.getString("body")),
                    id);
            return Optional.ofNullable(note);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Counts every note after a short artificial delay, used by the "pool stress" sample action to
     * briefly hold a JDBC connection under concurrent load so the Database Connection Pools panel has
     * something to show.
     */
    public int countWithDelay(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Integer count = jdbcTemplate.queryForObject("select count(*) from sample_note", Integer.class);
        return count == null ? 0 : count;
    }
}
