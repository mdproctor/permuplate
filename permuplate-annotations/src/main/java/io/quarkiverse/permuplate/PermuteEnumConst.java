package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands a sentinel enum constant into a sequence of constants per permutation.
 *
 * <p>
 * The annotated constant is removed and replaced by constants generated from the
 * inner loop [{@code from}, {@code to}]. Each constant's name is controlled by the
 * {@code name} attribute (a JEXL template). Optional constructor arguments are
 * provided via {@code args} (also a JEXL template; comma-separated, without parens).
 *
 * <p>
 * An empty range (from &gt; to) removes the sentinel with no replacement.
 */
@Target(ElementType.FIELD) // Enum constants are ElementType.FIELD in APT
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteEnumConst {
    String varName();

    String from();

    String to();

    String name();

    String args() default "";
}
