package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

public record HibernateRepositoryModel(
        String repositoryInterface,
        Class<?> domainType,
        List<HibernateRepositoryMethodModel> methods,
        boolean standardJpaNewness) {

    public HibernateRepositoryModel(
            String repositoryInterface, Class<?> domainType, List<HibernateRepositoryMethodModel> methods) {
        this(repositoryInterface, domainType, methods, false);
    }

    public HibernateRepositoryModel {
        methods = List.copyOf(methods);
    }
}
