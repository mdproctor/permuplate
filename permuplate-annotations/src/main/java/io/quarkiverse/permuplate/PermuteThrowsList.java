package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/** Container annotation for repeatable {@link PermuteThrows}. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteThrowsList {
    PermuteThrows[] value();
}
