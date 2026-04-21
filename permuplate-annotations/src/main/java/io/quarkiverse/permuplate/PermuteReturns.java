package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container annotation for repeatable {@link PermuteReturn}. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteReturns {
    PermuteReturn[] value();
}
