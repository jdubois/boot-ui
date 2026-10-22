package org.hibernate.envers;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/** Test stand-in with the shape of Hibernate Envers' revision mapped superclass; the engine does not compile against Envers. */
@MappedSuperclass
public class RevisionMapping {

    @Id
    @GeneratedValue
    private int id;

    @Column(name = "REVTSTMP")
    private long timestamp;

    @Override
    public boolean equals(Object other) {
        return other instanceof RevisionMapping revision && id == revision.id && timestamp == revision.timestamp;
    }

    @Override
    public int hashCode() {
        return 31 * id + Long.hashCode(timestamp);
    }
}
