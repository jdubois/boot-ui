package io.github.jdubois.bootui.spi;

import java.util.List;
import java.util.Objects;

/** A datasource inventory that preserves individual discovery failures alongside readable candidates. */
public record DatabaseAdvisorDataSourceDiscovery(List<NamedDataSource> dataSources, List<Failure> failures) {

    public DatabaseAdvisorDataSourceDiscovery {
        dataSources = List.copyOf(dataSources);
        failures = List.copyOf(failures);
        for (NamedDataSource dataSource : dataSources) {
            Objects.requireNonNull(dataSource.name(), "Datasource name");
            Objects.requireNonNull(dataSource.dataSource(), "Datasource handle");
        }
    }

    public record Failure(String name, String message) {
        public Failure {
            Objects.requireNonNull(name, "Failed datasource name");
        }
    }

    /** Legacy list-only consumers must not silently lose failed candidates. */
    public List<NamedDataSource> requireComplete() {
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Datasource discovery was incomplete.");
        }
        return dataSources;
    }
}
