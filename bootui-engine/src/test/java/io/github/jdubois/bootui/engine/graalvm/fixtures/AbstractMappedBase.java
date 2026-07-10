package io.github.jdubois.bootui.engine.graalvm.fixtures;

import jakarta.persistence.MappedSuperclass;

/** Verifies that abstract persistence base types are retained in generated reflection metadata. */
@MappedSuperclass
public abstract class AbstractMappedBase {

    protected String id;
}
