package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Drives the outer permutation loop on a class or interface (top-level or nested
 * static), or on a method.
 *
 * <p>
 * <b>Type permutation</b> ({@code @Permute} on a class or interface) — for each
 * combination of the permutation variables, clones the type declaration, applies all
 * transformations, and writes a new source file. Nested types are promoted to
 * top-level unless {@code inline = true} is set.
 *
 * <p>
 * <b>Inline generation</b> ({@code inline = true}, nested class only) — instead of
 * writing separate top-level files, all generated permutations are written as nested
 * siblings inside the parent class. Requires {@code permuplate-maven-plugin}; the APT
 * annotation processor reports a compile error if it encounters {@code inline = true}.
 *
 * <pre>{@code
 * public class Handlers {
 *     &#64;Permute(varName = "i", from = 2, to = 5,
 *              className = "Handler${i}",
 *              inline = true,
 *              keepTemplate = true)
 *     public static class Handler1 { ... }
 * }
 * }</pre>
 *
 * Generates {@code Handlers.Handler2} through {@code Handlers.Handler5} as nested
 * classes inside {@code Handlers}. If {@code keepTemplate = true}, {@code Handler1}
 * is also retained in the output.
 *
 * <p>
 * <b>On a method</b> — generates a single new class containing one overload of the
 * method per combination. {@code inline} is not supported on methods.
 *
 * <p>
 * <b>className prefix rule (type permutation only):</b> the leading literal part of
 * {@code className} (everything before the first {@code ${...}}) must be a prefix of
 * the template class's simple name. The rule is skipped when {@code className} starts
 * with a {@code ${...}} variable expression, and does not apply when {@code @Permute}
 * is placed on a method.
 *
 * <p>
 * <b>String variables:</b> {@code strings} entries are {@code "key=value"} pairs
 * providing fixed named string constants in all {@code ${...}} expressions.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Permute {
    /** The primary integer loop variable name (e.g. {@code "i"}). */
    String varName();

    /** Inclusive lower bound for the primary variable. */
    int from();

    /** Inclusive upper bound for the primary variable. Must be &gt;= {@code from}. */
    int to();

    /**
     * Output type/class name template. For type permutation, evaluated per combination
     * (e.g. {@code "Join${i}"}). For method permutation, a fixed class name
     * (e.g. {@code "MultiJoin"}) containing all overloads.
     */
    String className();

    /**
     * Named string constants available in all {@code ${...}} expressions alongside
     * {@code varName}. Each entry must be in {@code "key=value"} format.
     * Keys must not match {@code varName} or any {@code extraVars} variable name.
     */
    String[] strings() default {};

    /**
     * Additional integer loop variables for cross-product generation.
     *
     * @see PermuteVar
     */
    PermuteVar[] extraVars() default {};

    /**
     * When {@code true}, generates permuted classes as nested siblings inside the
     * parent class rather than as separate top-level files.
     *
     * <p>
     * Only valid on nested static classes. Requires {@code permuplate-maven-plugin};
     * the APT annotation processor reports a compile error if this is set to
     * {@code true}.
     *
     * <p>
     * Template files with {@code inline = true} must be placed in
     * {@code src/main/permuplate/} (the plugin's template directory) so they are
     * not compiled directly by javac. The augmented parent class is written to
     * {@code target/generated-sources/permuplate/} instead.
     */
    boolean inline() default false;

    /**
     * When {@code true} and {@code inline = true}, the template class itself is
     * retained in the generated parent alongside the permuted classes. When
     * {@code false} (default), the template class is removed — it was only a scaffold
     * and the generated classes replace it entirely.
     *
     * <p>
     * Has no effect when {@code inline = false}.
     */
    boolean keepTemplate() default false;
}
