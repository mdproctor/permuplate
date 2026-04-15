package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container annotation for repeatable {@link PermuteFilter}. */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface PermuteFilters {
    PermuteFilter[] value();
}
