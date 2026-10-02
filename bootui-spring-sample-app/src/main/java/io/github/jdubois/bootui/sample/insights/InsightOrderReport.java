package io.github.jdubois.bootui.sample.insights;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One order of the lazy report: its line count is read when Jackson asks for it, while the response is written, as an
 * open-session-in-view lazy association would be ({@code lazy-sql-after-handler}, M3-6).
 */
public final class InsightOrderReport {

    private final long id;
    private final JdbcTemplate jdbc;

    InsightOrderReport(long id, JdbcTemplate jdbc) {
        this.id = id;
        this.jdbc = jdbc;
    }

    public long getId() {
        return id;
    }

    public Integer getLines() {
        return jdbc.queryForObject("select count(*) from insight_order_lines where order_id = ?", Integer.class, id);
    }
}
