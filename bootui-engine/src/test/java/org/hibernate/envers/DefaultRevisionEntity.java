package org.hibernate.envers;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Test stand-in with the shape of the entity Envers registers for revisions: public, final, mapped to
 * {@code REVINFO}, with no {@code @Version}.
 */
@Entity
@Table(name = "REVINFO")
public final class DefaultRevisionEntity extends RevisionMapping {}
