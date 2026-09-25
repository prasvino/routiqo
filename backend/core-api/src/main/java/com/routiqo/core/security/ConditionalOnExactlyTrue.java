package com.routiqo.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** Matches only when every named property is exactly {@code true}; see {@link FeatureFlags}. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(OnExactlyTrueCondition.class)
public @interface ConditionalOnExactlyTrue {
    String[] value();
}
