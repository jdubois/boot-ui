package com.example.bootui.it.advisor;

import io.quarkus.arc.Lock;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Stereotype;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class ResolvedApplicationBeans {
    private ResolvedApplicationBeans() {}

    @ApplicationScoped
    @Unremovable
    public static class Parent {
        public long inherited;
    }

    @Discovered
    @Unremovable
    public static class Child extends Parent {
        public long own;
    }

    @Stereotype
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Discovered {}

    @Stereotype
    @ApplicationScoped
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface SharedService {}

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD})
    public @interface Collaborator {}

    @Singleton
    @Collaborator
    public static class Dependency {}

    @SharedService
    @Unremovable
    @Lock
    public static class Stereotyped {
        @Collaborator
        public Dependency dependency;

        public long exposed;
        public final List<String> mutable = new ArrayList<>();
        public final AtomicLong atomic = new AtomicLong();
        public final String immutable = "constant";
        private long privateState;
    }

    @ApplicationScoped
    @IfBuildProfile("bootui-advisor-never-active")
    @Unremovable
    public static class Disabled {
        public long excluded;
    }

    @Path("/advisor-fixture/shared")
    public static class DefaultResource {
        public long shared;

        @GET
        public String get() {
            throw new AssertionError("Advisor metadata must not invoke resource methods");
        }
    }

    @Path("/advisor-fixture/request")
    public static class ParameterResource {
        @QueryParam("value")
        public String parameter;

        public long requestState;

        @GET
        public String get() {
            throw new AssertionError("Advisor metadata must not invoke resource methods");
        }
    }

    @Path("/advisor-fixture/context")
    public static class ContextResource {
        @Context
        public SecurityContext context;

        public long shared;

        @GET
        public String get() {
            throw new AssertionError("Advisor metadata must not invoke resource methods");
        }
    }
}
