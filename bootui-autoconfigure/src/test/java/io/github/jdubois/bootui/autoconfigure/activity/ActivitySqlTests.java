package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActivitySqlTests {

    @Test
    void dropsCategoryPrefixWhenStatementAlreadyStartsWithIt() {
        assertThat(ActivitySql.summarize("SELECT", "select p.id from product p"))
                .isEqualTo("select p.id from product p");
        assertThat(ActivitySql.summarize("UPDATE", "UPDATE product SET active = false"))
                .isEqualTo("UPDATE product SET active = false");
        assertThat(ActivitySql.summarize("INSERT", "insert into product values (1)"))
                .isEqualTo("insert into product values (1)");
    }

    @Test
    void keepsCategoryPrefixWhenItAddsInformation() {
        assertThat(ActivitySql.summarize("DDL", "create table product (id bigint)"))
                .isEqualTo("DDL create table product (id bigint)");
        assertThat(ActivitySql.summarize("OTHER", "call sp_refresh()")).isEqualTo("OTHER call sp_refresh()");
    }

    @Test
    void degradesGracefullyForBlankInput() {
        assertThat(ActivitySql.summarize("SELECT", "")).isEqualTo("SELECT");
        assertThat(ActivitySql.summarize("", "select 1")).isEqualTo("select 1");
        assertThat(ActivitySql.summarize(null, "select 1")).isEqualTo("select 1");
    }

    @Test
    void keepsPrefixWhenStatementShorterThanCategory() {
        assertThat(ActivitySql.summarize("SELECT", "se")).isEqualTo("SELECT se");
    }
}
