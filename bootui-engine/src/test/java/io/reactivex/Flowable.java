package io.reactivex;

/**
 * Minimal test stub standing in for the absent RxJava 2 library, so ARCH-SPRING-012 fixtures can
 * compile and exercise the "old io.reactivex.* namespace is not a Spring-recognized deferred
 * return type" detection (Spring's {@code ScheduledAnnotationReactiveSupport} only recognizes
 * RxJava 3's {@code io.reactivex.rxjava3.*} namespace). Not the real library.
 */
public class Flowable<T> {}
