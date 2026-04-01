package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Renames a declaration's type and identifier for each permutation, and propagates
 * the rename to all usages of the original name within the declaration's scope.
 *
 * <p>
 * Supported placements and their rename scope:
 * <ul>
 * <li><b>Field</b> — entire class body (all methods and constructors)</li>
 * <li><b>Constructor parameter</b> — the constructor body only</li>
 * <li><b>For-each loop variable</b> — the loop body only</li>
 * </ul>
 *
 * <p>
 * Fields are processed before constructor parameters, and constructor parameters
 * before for-each variables, so that broader-scope renames are already applied
 * when narrower scopes are walked.
 *
 * <p>
 * Both {@code type} and {@code name} support {@code ${varName}} interpolation
 * and arithmetic expressions such as {@code ${i-1}}. The static
 * (non-{@code ${...}}) part of each must be a prefix of the actual declaration's
 * type and name respectively — a mismatch is reported as a compile error.
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
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.PARAMETER })
public @interface PermuteDeclr {
    String type();

    String name();
}
