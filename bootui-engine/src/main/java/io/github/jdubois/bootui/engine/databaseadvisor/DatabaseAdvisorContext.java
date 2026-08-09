package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.List;

/**
 * Everything a Database Advisor rule needs: the physical schema read from every discovered
 * {@code DataSource}, plus (when available) the host application's mapped Hibernate entities.
 *
 * <p>{@code hibernateAvailable} is {@code false} whenever no {@code EntityManagerFactory}/Hibernate
 * metamodel could be read for the persistence unit(s) sharing these datasources; the Hibernate
 * cross-reference rules must skip (not silently drop) in that case.</p>
 */
record DatabaseAdvisorContext(
        List<SchemaSnapshot> schemas, boolean hibernateAvailable, List<MappedEntityFacts> hibernateEntities) {

    DatabaseAdvisorContext {
        schemas = List.copyOf(schemas);
        hibernateEntities = List.copyOf(hibernateEntities);
    }

    List<SchemaSnapshot> availableSchemas() {
        return schemas.stream().filter(SchemaSnapshot::available).toList();
    }

    int tableCount() {
        return availableSchemas().stream()
                .mapToInt(schema -> schema.tables().size())
                .sum();
    }
}
