package io.github.jdubois.bootui.engine.databaseadvisor;

/** A MySQL column whose reported character set is not {@code utf8mb4} (e.g. legacy {@code utf8}/{@code latin1}). */
record MySqlNonUtf8mb4Column(String tableName, String columnName, String characterSetName) {}
