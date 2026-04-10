package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a JEXL-evaluated import statement to each generated class.
 *
 * <p>
 * Placed on the template class. For each permutation, the {@code value} string
 * is evaluated with the current permutation context and added as an import to
 * the generated compilation unit. The annotation itself is stripped from the
 * generated output.
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * &#64;Permute(varName = "i", from = "3", to = "10", className = "Join${i}First", inline = true)
 * &#64;PermuteImport("org.drools.core.function.BaseTuple.Tuple${i}")
 * &#64;PermuteImport("org.drools.core.RuleOOPathBuilder.Path${i}")
 * public static class Join2First<...> { ... }
 * // Generated Join4First gets: import BaseTuple.Tuple4; import RuleOOPathBuilder.Path4;
 * }</pre>
 */
@Repeatable(PermuteImports.class)
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteImport {
    /**
     * JEXL-interpolated fully-qualified import string added to each generated class.
     * E.g. {@code "org.drools.core.function.BaseTuple.Tuple${i}"}.
     */
    String value();
}
