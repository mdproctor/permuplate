package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a named body fragment that can be substituted into {@link PermuteBody}
 * strings via {@code ${name}} references.
 *
 * <p>
 * Fragments are declared on the template class or an enclosing type.
 * The {@code value} is JEXL-evaluated with the current permutation context before
 * substitution, so it may contain {@code ${i}}, {@code ${alpha(k)}}, etc.
 *
 * <p>
 * Substitution occurs before {@link PermuteBody} strings are parsed and JEXL-evaluated,
 * so fragments may contain Java source code including annotations and type expressions.
 *
 * <p>
 * <b>Maven plugin only.</b> The APT processor ignores this annotation.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(PermuteBodyFragments.class)
public @interface PermuteBodyFragment {
    /**
     * Reference key used as {@code ${name}} in {@link PermuteBody#body()}.
     * Must not clash with JEXL built-in function names ({@code alpha}, {@code lower},
     * {@code typeArgList}, {@code capitalize}, {@code decapitalize}, {@code max},
     * {@code min}) or loop variable names — such names are silently overwritten by
     * the JEXL context after fragment substitution.
     */
    String name();

    /** Java code fragment. JEXL expressions (e.g. {@code ${i}}) are evaluated first. */
    String value();
}
