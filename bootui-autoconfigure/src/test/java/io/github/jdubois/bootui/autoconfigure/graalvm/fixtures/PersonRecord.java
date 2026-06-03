package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

/** A record: a reflection metadata candidate (records need reflection in native images). */
public record PersonRecord(String firstName, String lastName) {}
