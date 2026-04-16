package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/** Container annotation for repeatable {@link PermuteAnnotation}. */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface PermuteAnnotations {
    PermuteAnnotation[] value();
}
