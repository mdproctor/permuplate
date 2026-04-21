package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares JEXL macro expressions that are available to all {@code @Permute} templates
 * nested within the annotated type. Macros are evaluated per-permutation with the current
 * loop variables in scope, in declaration order (later macros may reference earlier ones).
 *
 * <p>
 * Format: same as {@link Permute#macros()} — each entry is {@code "name=jexlExpr"}.
 *
 * <p>
 * Innermost macros take precedence: a template's own {@code macros=} attribute shadows
 * a container macro with the same name.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {
 *     &#64;code
 *     &#64;PermuteMacros({ "alphaList=typeArgList(1,i,'alpha')" })
 *     public class MyContainer {
 *         @Permute(varName = "i", from = "1", to = "6", className = "MyClass${i}", inline = true)
 *         public static class MyClass1<A> {
 *             // alphaList is available here without repeating macros= on @Permute
 *         }
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteMacros {
    /** Macro definitions in {@code "name=jexlExpr"} format. */
    String[] value();
}
