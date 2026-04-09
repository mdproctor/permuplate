package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Renames a declaration's type and (optionally) identifier for each permutation,
 * and propagates the rename to all usages of the original name within the
 * declaration's scope.
 *
 * <p>
 * Supported placements and their rename scope:
 * <ul>
 * <li><b>Field</b> — entire class body (all methods and constructors)</li>
 * <li><b>Constructor parameter</b> — the constructor body only</li>
 * <li><b>For-each loop variable</b> — the loop body only</li>
 * <li><b>Method parameter</b> — the method body only; name rename is optional</li>
 * <li><b>Object creation expression (TYPE_USE)</b> — updates the constructor class name
 * in {@code new @PermuteDeclr(type="X${i+1}") X${i}<>(args)} expressions</li>
 * </ul>
 *
 * <p>
 * Fields are processed before constructor parameters, and constructor parameters
 * before for-each variables, so that broader-scope renames are already applied
 * when narrower scopes are walked.
 *
 * <p>
 * Both {@code type} and {@code name} (when non-empty) support {@code ${varName}}
 * interpolation and arithmetic expressions such as {@code ${i-1}}. The static
 * (non-{@code ${...}}) part of each must be a prefix of the actual declaration's
 * type and name respectively — a mismatch is reported as a compile error.
 *
 * <p>
 * For <b>method parameters</b>, {@code name} may be omitted (defaults to {@code ""})
 * to change only the type, leaving the parameter name unchanged. This is the common
 * case in APT mode where the sentinel parameter uses {@code Object} as its type:
 *
 * <pre>{@code
 * // APT mode: only the type changes, name stays "src"
 * public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
 * }</pre>
 *
 * <p>
 * Examples:
 *
 * <pre>{@code
 * // Field:
 * private @PermuteDeclr(type="Callable${i}", name="c${i}") Callable2 c2;
 *
 * // Constructor parameter:
 * public Join2(@PermuteDeclr(type="Callable${i}", name="c${i}") Callable2 c2) { ... }
 *
 * // For-each loop variable:
 * for (@PermuteDeclr(type="Object", name="o${i}") Object o2 : right) { ... }
 *
 * // Method parameter (type only — name unchanged):
 * public Object join(@PermuteDeclr(type="Source<T${i+1}>") Object src) { ... }
 *
 * // Object creation expression (TYPE_USE) — updates constructor class name:
 * return new @PermuteDeclr(type="Join${i+1}First") Join3First<>(end());
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.PARAMETER, ElementType.TYPE_USE })
public @interface PermuteDeclr {
    String type();

    /** New name template. Empty string (the default) means keep the original name. */
    String name() default "";
}
