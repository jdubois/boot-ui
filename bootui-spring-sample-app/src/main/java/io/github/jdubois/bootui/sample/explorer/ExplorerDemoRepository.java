package io.github.jdubois.bootui.sample.explorer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExplorerDemoRepository {
    private final JdbcClient jdbc;

    public ExplorerDemoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String name(long id) {
        return jdbc.sql("select name from sample_products where id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public void fail() {
        throw new ExplorerDemoFailure();
    }
}
