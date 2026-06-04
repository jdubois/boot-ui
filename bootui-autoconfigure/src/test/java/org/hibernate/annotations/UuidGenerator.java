package org.hibernate.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface UuidGenerator {

    Style style() default Style.AUTO;

    enum Style {
        AUTO,
        RANDOM,
        TIME
    }
}
