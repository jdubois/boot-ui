package io.github.jdubois.bootui.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_advisor_legacy_tickets")
// Hibernate Advisor demo: the TABLE generator intentionally triggers HIB-ID-002.
public class SampleLegacyTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE)
    private Long id;

    private String title;

    protected SampleLegacyTicket() {}

    public SampleLegacyTicket(String title) {
        this.title = title;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }
}
