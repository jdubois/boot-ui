package io.github.jdubois.bootui.core.dto;

/** Bounded safe diagnostic; never contains JDBC exception messages, SQL, URLs or driver properties. */
public record MySqlDiagnosticDto(String source, String level, String message) {}
