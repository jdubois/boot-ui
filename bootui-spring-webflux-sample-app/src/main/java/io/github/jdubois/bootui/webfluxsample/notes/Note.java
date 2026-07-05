package io.github.jdubois.bootui.webfluxsample.notes;

/** A simple note row, stored in the Flyway-managed {@code sample_note} table. */
public record Note(Long id, String title, String body) {}
