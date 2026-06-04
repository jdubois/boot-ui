package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import java.util.List;
import org.springframework.core.env.Environment;

record HibernateAdvisorContext(
        List<HibernateEntityModel> entities, List<HibernateRepositoryModel> repositories, Environment environment) {

    HibernateAdvisorContext {
        entities = List.copyOf(entities);
        repositories = List.copyOf(repositories);
    }

    boolean hasAssociations() {
        return entities.stream()
                .flatMap(entity -> entity.attributes().stream())
                .anyMatch(HibernateAttributeModel::isAssociation);
    }

    int associationCount() {
        return (int) entities.stream()
                .flatMap(entity -> entity.attributes().stream())
                .filter(HibernateAttributeModel::isAssociation)
                .count();
    }

    boolean hasBatchSizeAnnotation() {
        return entities.stream().anyMatch(HibernateEntityModel::hasBatchSizeAnnotation)
                || entities.stream()
                        .flatMap(entity -> entity.attributes().stream())
                        .anyMatch(HibernateAttributeModel::hasBatchSizeAnnotation);
    }

    Integer defaultBatchFetchSize() {
        for (String key : List.of(
                "spring.jpa.properties.hibernate.default_batch_fetch_size", "hibernate.default_batch_fetch_size")) {
            Integer value = integerProperty(key);
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    String firstProperty(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    Integer firstIntegerProperty(String... keys) {
        for (String key : keys) {
            Integer value = integerProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    boolean isPropertyTrue(String... keys) {
        String value = firstProperty(keys);
        return value != null && "true".equalsIgnoreCase(value);
    }

    boolean isPropertyFalse(String... keys) {
        String value = firstProperty(keys);
        return value != null && "false".equalsIgnoreCase(value);
    }

    private Integer integerProperty(String key) {
        try {
            return environment.getProperty(key, Integer.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
