package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

public record HibernateRepositoryModel(
        String repositoryInterface, Class<?> domainType, List<HibernateRepositoryMethodModel> methods) {

    public HibernateRepositoryModel {
        methods = List.copyOf(methods);
    }
}
