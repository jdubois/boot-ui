package io.github.jdubois.bootui.sample;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sample_advisor_settings")
// Hibernate Advisor demo: this settings entity intentionally trips element-collection mapping checks.
public class SampleAppPreferences {

    @Id
    @SequenceGenerator(name = "sample_advisor_settings_seq", allocationSize = 50)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sample_advisor_settings_seq")
    private Long id;

    private String owner;

    // Intentionally fetched eagerly to trigger HIB-FETCH-001 (eager fetching should stay explicit).
    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> enabledFeatures = new ArrayList<>();

    // Intentionally a List<> without @OrderColumn/@OrderBy to trigger HIB-MAP-010 (element collection
    // rewrite-on-change).
    @ElementCollection
    private List<String> recentSearches = new ArrayList<>();

    protected SampleAppPreferences() {}

    public SampleAppPreferences(String owner) {
        this.owner = owner;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public List<String> getEnabledFeatures() {
        return enabledFeatures;
    }

    public List<String> getRecentSearches() {
        return recentSearches;
    }
}
