package io.github.jdubois.bootui.sample.advisor.hibernate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

@Entity
@Table(name = "sample_advisor_audit_entries")
// Hibernate Advisor demo: this audit entity intentionally trips several mapping and identifier checks.
public class SampleAuditEntry {

    // Intentionally uses @GeneratedValue without an explicit strategy on a UUID identifier to trigger
    // HIB-ID-004 (default strategy) and HIB-ID-005 (UUID without @UuidGenerator).
    @Id
    @GeneratedValue
    private UUID id;

    // Intentionally a public field to trigger HIB-ENTITY-005 (public persistent field bypasses Hibernate accessors).
    public String actor;

    // Intentionally an unbounded String column to trigger HIB-MAP-013 (missing explicit length).
    private String summary;

    // Intentionally @Lob without @Basic(fetch=LAZY); HIB-FETCH-005 only fires when enhancement can honor LAZY.
    @Lob
    private String payload;

    // Intentionally a BigDecimal column without precision/scale to trigger HIB-MAP-014.
    private BigDecimal amount;

    // Intentionally java.util.Date instead of java.time to trigger HIB-MAP-015 (legacy temporal type).
    private Date recordedAt;

    protected SampleAuditEntry() {}

    public SampleAuditEntry(String actor, String summary, String payload, BigDecimal amount, Date recordedAt) {
        this.actor = actor;
        this.summary = summary;
        this.payload = payload;
        this.amount = amount;
        this.recordedAt = recordedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getSummary() {
        return summary;
    }

    public String getPayload() {
        return payload;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Date getRecordedAt() {
        return recordedAt;
    }
}
