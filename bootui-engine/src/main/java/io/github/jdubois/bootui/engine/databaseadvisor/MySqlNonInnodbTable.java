package io.github.jdubois.bootui.engine.databaseadvisor;

/** A MySQL table whose storage engine is not InnoDB (no foreign-key enforcement, no MVCC). */
record MySqlNonInnodbTable(String tableName, String engine) {}
