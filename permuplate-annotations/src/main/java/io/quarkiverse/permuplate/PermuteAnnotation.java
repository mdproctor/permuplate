package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/**
 * Adds a Java annotation to the generated type, method, or field per permutation.
 * {@code value} is a JEXL-evaluated annotation literal (e.g. {@code "@Deprecated(since=\"${i}\")"}).
 * {@code when} is an optional JEXL boolean — empty string means always apply.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * &#64;Permute(varName="i", from="1", to="6", className="Callable${i}", strings={"max=6"})
 * @PermuteAnnotation(when="${i == 1}", value="@FunctionalInterface")
 * &#64;PermuteAnnotation(when="${i == max}", value="@Deprecated(since=\"use higher arity\")")
 * public interface Callable1 { ... }
 * }
 * </pre>
 */
@Repeatable(PermuteAnnotations.class)
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface PermuteAnnotation {
    /** JEXL-evaluated Java annotation literal to add. Must parse as a valid annotation. */
    String value();

    /** JEXL boolean condition. Empty string means always apply. */
    String when() default "";
}
