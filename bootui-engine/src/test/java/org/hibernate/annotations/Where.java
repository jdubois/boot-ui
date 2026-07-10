package org.hibernate.annotations;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Hibernate 6 compatibility fixture for the annotation removed in Hibernate ORM 7. */
@Target({TYPE, METHOD, FIELD})
@Retention(RUNTIME)
public @interface Where {
    String clause();
}
