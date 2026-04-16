package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/** Container annotation for repeatable {@link PermuteSource}. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteSources {
    PermuteSource[] value();
}
