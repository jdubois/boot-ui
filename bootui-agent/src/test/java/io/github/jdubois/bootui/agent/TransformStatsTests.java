package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.instrument.ClassFileTransformer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransformStatsTests {

    @Test
    void configuringATransformerFirstDefinesWhatTheFirstTransformationLoads() {
        new TransformStats().configure(new AgentBuilder.Default());

        assertThat(TransformStats.transformationWarmedUp())
                .as("the throwaway transformer applied its advice to a generated class")
                .isTrue();
    }

    @Test
    void ignoresUnrelatedClassesBeforeParsingTheirBytes() throws Exception {
        TransformStats stats = new TransformStats();
        AtomicBoolean ignored = new AtomicBoolean();
        ClassFileTransformer transformer = stats.configure(new AgentBuilder.Default())
                .ignore(type -> {
                    ignored.set(true);
                    return type.getName().startsWith("unrelated.");
                })
                .type(ElementMatchers.any())
                .transform((builder, type, loader, module, domain) -> builder)
                .makeRaw();

        assertThat(transformer.transform(getClass().getClassLoader(), "unrelated/Unparsed", null, null, new byte[] {0}))
                .isNull();
        assertThat(ignored).isTrue();
        Map<String, Object> status = new LinkedHashMap<>();
        stats.putInto(status);
        assertThat(status).containsEntry("failed", 0).containsEntry("transformed", 0);
    }

    @Test
    void stillTransformsMatchedClasses() throws Exception {
        TransformStats stats = new TransformStats();
        byte[] bytes = new ByteBuddy()
                .subclass(Object.class)
                .name("matched.Application")
                .make()
                .getBytes();
        ClassFileTransformer transformer = stats.configure(new AgentBuilder.Default())
                .ignore(ElementMatchers.none())
                .type(ElementMatchers.named("matched.Application"))
                .transform((builder, type, loader, module, domain) ->
                        builder.visit(Advice.to(TransformStats.WarmAdvice.class).on(ElementMatchers.isConstructor())))
                .makeRaw();

        assertThat(transformer.transform(getClass().getClassLoader(), "matched/Application", null, null, bytes))
                .isNotNull();
        Map<String, Object> status = new LinkedHashMap<>();
        stats.putInto(status);
        assertThat(status).containsEntry("failed", 0).containsEntry("transformed", 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothPoolOverloadsDeferResolutionAndCacheBootstrapFallbacks(boolean named) {
        TransformStats stats = new TransformStats();
        List<String> located = new ArrayList<>();
        ClassFileLocator locator = new ClassFileLocator() {
            @Override
            public Resolution locate(String name) {
                located.add(name);
                return new Resolution.Illegal(name);
            }

            @Override
            public void close() {}
        };
        AgentBuilder.PoolStrategy strategy = stats.new BootstrapFallbackPoolStrategy();
        TypePool pool = named
                ? strategy.typePool(locator, getClass().getClassLoader(), "unrelated.Unparsed")
                : strategy.typePool(locator, getClass().getClassLoader());

        assertThat(pool.describe("unrelated.Unparsed").resolve().getName()).isEqualTo("unrelated.Unparsed");
        assertThat(located).isEmpty();
        assertThat(pool.describe("java.lang.Runnable").resolve().isInterface()).isTrue();
        assertThat(pool.describe("java.lang.Runnable").resolve().isInterface()).isTrue();
        assertThat(pool.describe(getClass().getName()).isResolved()).isFalse();
        assertThat(located).containsExactly("java.lang.Runnable", getClass().getName());
        Map<String, Object> status = new LinkedHashMap<>();
        stats.putInto(status);
        assertThat(status)
                .containsEntry("poolFallbacks", 1)
                .containsEntry("fallbackTypes", List.of("java.lang.Runnable"));
    }
}
